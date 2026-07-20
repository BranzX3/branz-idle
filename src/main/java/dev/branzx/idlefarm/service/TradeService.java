package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.worker.WorkerRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protected direct trade escrow. Any offer mutation resets confirmations;
 * settlement writes a unique receipt before both item bundles are delivered.
 * Coins, Credits and bound Starter Workers have no route into escrow.
 */
public final class TradeService implements Listener {

    public record Result(boolean success, String message) {
        static Result ok(String message) { return new Result(true, message); }
        static Result fail(String message) { return new Result(false, message); }
    }

    public record View(UUID partner, List<ItemStack> mine, List<ItemStack> theirs,
                       boolean mineConfirmed, boolean theirsConfirmed) {
    }

    private static final class Session {
        final String id = UUID.randomUUID().toString();
        final UUID a;
        final UUID b;
        final List<ItemStack> aItems = new ArrayList<>();
        final List<ItemStack> bItems = new ArrayList<>();
        boolean aConfirmed;
        boolean bConfirmed;

        Session(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final AuditService audit;
    private final WorkerService workers;
    private final GameDesignService design;
    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public TradeService(IdleFarmPlugin plugin, Database database, AuditService audit,
                        WorkerService workers, GameDesignService design) {
        this.plugin = plugin;
        this.database = database;
        this.audit = audit;
        this.workers = workers;
        this.design = design;
    }

    public Result request(Player requester, Player target) {
        if (requester.equals(target)) return Result.fail("You cannot trade with yourself.");
        if (sessions.containsKey(requester.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            return Result.fail("One of you is already trading.");
        }
        requests.put(target.getUniqueId(), requester.getUniqueId());
        target.sendMessage("§e" + requester.getName() + " requested a protected trade. "
                + "Use /idle trade accept " + requester.getName());
        return Result.ok("Trade request sent to " + target.getName() + ".");
    }

    public Result accept(Player target, Player requester) {
        UUID pending = requests.get(target.getUniqueId());
        if (pending == null || !pending.equals(requester.getUniqueId())) {
            return Result.fail("No matching trade request.");
        }
        requests.remove(target.getUniqueId());
        Session session = new Session(requester.getUniqueId(), target.getUniqueId());
        sessions.put(session.a, session);
        sessions.put(session.b, session);
        return Result.ok("Trade opened. Hold an item and use /idle trade offer.");
    }

    public Result offerHeld(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) return Result.fail("Hold the item stack to offer.");
        WorkerRecord worker = workers.fromItem(held);
        if (worker != null && held.getAmount() != 1) {
            design.telemetry(player.getUniqueId(), "DUPLICATE_WORKER_UUID_ATTEMPT",
                    "{\"worker\":\"" + worker.getWorkerUuid() + "\"}");
            return Result.fail("Worker contracts must be a single authoritative item.");
        }
        if (worker != null && !WorkerRecord.STATE_ITEM.equals(worker.getState())) {
            return Result.fail("That worker token is no longer authoritative.");
        }
        if (worker != null && java.util.stream.Stream.concat(session.aItems.stream(), session.bItems.stream())
                .map(workers::fromItem).filter(java.util.Objects::nonNull)
                .anyMatch(existing -> existing.getWorkerUuid().equals(worker.getWorkerUuid()))) {
            design.telemetry(player.getUniqueId(), "DUPLICATE_WORKER_UUID_ATTEMPT",
                    "{\"worker\":\"" + worker.getWorkerUuid() + "\"}");
            return Result.fail("That worker UUID is already in escrow.");
        }
        if (worker != null && design.isStarterWorker(worker.getWorkerUuid())) {
            return Result.fail("The account-bound Starter Worker cannot be traded.");
        }
        if (worker != null && design.isWorkerLocked(worker.getWorkerUuid())) {
            return Result.fail("Favorite/locked workers cannot be traded.");
        }
        List<ItemStack> offer = offerOf(session, player.getUniqueId());
        if (offer.size() >= 18) return Result.fail("Trade offer is full (18 stacks).");
        offer.add(held.clone());
        player.getInventory().setItemInMainHand(null);
        resetConfirmations(session);
        notifyBoth(session, "§eTrade offer changed; both confirmations reset.");
        return Result.ok("Added stack to escrow. Use /idle trade view or confirm.");
    }

    public Result confirm(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        if (player.getUniqueId().equals(session.a)) session.aConfirmed = true;
        else session.bConfirmed = true;
        if (!session.aConfirmed || !session.bConfirmed) {
            notifyOther(session, player.getUniqueId(), "§a" + player.getName() + " confirmed the trade.");
            return Result.ok("Confirmed. Waiting for the other player.");
        }
        return settle(session);
    }

    public Result removeOffer(Player player, int index) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        List<ItemStack> offer = offerOf(session, player.getUniqueId());
        if (index < 0 || index >= offer.size()) return Result.fail("Offer changed; refresh the trade.");
        ItemStack returned = offer.remove(index);
        deliver(player, List.of(returned));
        resetConfirmations(session);
        notifyBoth(session, "§eTrade offer changed; both confirmations reset.");
        return Result.ok("Stack removed from escrow.");
    }

    public Result cancel(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        returnItems(session.a, session.aItems);
        returnItems(session.b, session.bItems);
        close(session);
        notifyBoth(session, "§cTrade cancelled; escrow items were returned.");
        return Result.ok("Trade cancelled.");
    }

    public View view(UUID player) {
        Session session = sessions.get(player);
        if (session == null) return null;
        boolean isA = player.equals(session.a);
        return new View(isA ? session.b : session.a,
                List.copyOf(isA ? session.aItems : session.bItems),
                List.copyOf(isA ? session.bItems : session.aItems),
                isA ? session.aConfirmed : session.bConfirmed,
                isA ? session.bConfirmed : session.aConfirmed);
    }

    public void shutdown() {
        for (Session session : new java.util.HashSet<>(sessions.values())) {
            returnItems(session.a, session.aItems);
            returnItems(session.b, session.bItems);
            close(session);
        }
        requests.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            cancel(event.getPlayer());
        }
        requests.remove(event.getPlayer().getUniqueId());
        requests.values().removeIf(event.getPlayer().getUniqueId()::equals);
    }

    private Result settle(Session session) {
        Player a = Bukkit.getPlayer(session.a);
        Player b = Bukkit.getPlayer(session.b);
        if (a == null || b == null) {
            if (a != null) cancel(a);
            else if (b != null) cancel(b);
            return Result.fail("Both players must remain online.");
        }
        String offerA = serialize(session.aItems);
        String offerB = serialize(session.bItems);
        // Ordered blocking commit: items are only delivered after the receipt
        // row is durable, and the write queue stays the single writer.
        boolean committed = database.executeTransaction("trade receipt " + session.id, connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idlefarm_trade_receipts "
                            + "(trade_id, player_a, player_b, offer_a, offer_b) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, session.id);
                insert.setString(2, session.a.toString());
                insert.setString(3, session.b.toString());
                insert.setString(4, offerA);
                insert.setString(5, offerB);
                insert.executeUpdate();
            }
        });
        if (!committed) {
            resetConfirmations(session);
            return Result.fail("Trade could not be committed; nothing moved.");
        }
        deliver(a, session.bItems);
        deliver(b, session.aItems);
        close(session);
        audit.log(session.a, "TRADE_COMPLETED", "{\"trade\":\"" + session.id
                + "\",\"partner\":\"" + session.b + "\"}");
        audit.log(session.b, "TRADE_COMPLETED", "{\"trade\":\"" + session.id
                + "\",\"partner\":\"" + session.a + "\"}");
        design.telemetry(session.a, "TRADE_COMPLETED", "{\"trade\":\"" + session.id + "\"}");
        design.telemetry(session.b, "TRADE_COMPLETED", "{\"trade\":\"" + session.id + "\"}");
        design.onTradeCompleted(session.a);
        design.onTradeCompleted(session.b);
        notifyBoth(session, "§aProtected trade completed. Receipt: " + session.id);
        return Result.ok("Trade completed. Receipt: " + session.id);
    }

    private List<ItemStack> offerOf(Session session, UUID player) {
        return player.equals(session.a) ? session.aItems : session.bItems;
    }

    private void resetConfirmations(Session session) {
        session.aConfirmed = false;
        session.bConfirmed = false;
    }

    private void close(Session session) {
        sessions.remove(session.a);
        sessions.remove(session.b);
    }

    private void deliver(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            var leftovers = player.getInventory().addItem(item);
            leftovers.values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
    }

    private void returnItems(UUID owner, List<ItemStack> items) {
        Player player = Bukkit.getPlayer(owner);
        if (player != null) deliver(player, items);
    }

    private void notifyBoth(Session session, String message) {
        Player a = Bukkit.getPlayer(session.a);
        Player b = Bukkit.getPlayer(session.b);
        if (a != null) a.sendMessage(message);
        if (b != null) b.sendMessage(message);
    }

    private void notifyOther(Session session, UUID actor, String message) {
        Player target = Bukkit.getPlayer(actor.equals(session.a) ? session.b : session.a);
        if (target != null) target.sendMessage(message);
    }

    private String serialize(List<ItemStack> items) {
        return items.stream()
                .map(item -> Base64.getEncoder().encodeToString(item.serializeAsBytes()))
                .reduce((left, right) -> left + ";" + right).orElse("");
    }
}
