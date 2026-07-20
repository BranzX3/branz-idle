package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.command.CommandLinks;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.TradeEscrowStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
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
        final List<TradeEscrowStore.Entry> aItems = new ArrayList<>();
        final List<TradeEscrowStore.Entry> bItems = new ArrayList<>();
        boolean aConfirmed;
        boolean bConfirmed;

        Session(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }
    }

    private final IdleFarmPlugin plugin;
    private final TradeEscrowStore escrow;
    private final AuditService audit;
    private final WorkerService workers;
    private final GameDesignService design;
    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public TradeService(IdleFarmPlugin plugin, Database database, AuditService audit,
                        WorkerService workers, GameDesignService design) {
        this.plugin = plugin;
        this.escrow = new TradeEscrowStore(database);
        this.audit = audit;
        this.workers = workers;
        this.design = design;
        int recovered = escrow.recoverInterruptedTrades();
        if (recovered > 0) {
            plugin.getLogger().warning("Recovered " + recovered
                    + " item stack(s) from interrupted trades; delivery is queued for login.");
        } else if (recovered < 0) {
            plugin.getLogger().severe("Trade escrow recovery failed; pending rows were left untouched.");
        }
    }

    public Result request(Player requester, Player target) {
        if (requester.equals(target)) return Result.fail("You cannot trade with yourself.");
        if (sessions.containsKey(requester.getUniqueId()) || sessions.containsKey(target.getUniqueId())) {
            return Result.fail("One of you is already trading.");
        }
        requests.put(target.getUniqueId(), requester.getUniqueId());
        target.sendMessage(Component.text(
                        requester.getName() + " requested a protected trade. ",
                        NamedTextColor.YELLOW)
                .append(CommandLinks.run("[Accept]",
                        "/idle trade accept " + requester.getName()))
                .append(Component.space())
                .append(CommandLinks.run("[Decline]", "/idle trade decline")));
        return Result.ok("Trade request sent to " + target.getName() + ".");
    }

    public Result decline(Player target) {
        UUID pending = requests.remove(target.getUniqueId());
        if (pending == null) {
            return Result.fail("No pending trade request.");
        }
        Player requester = Bukkit.getPlayer(pending);
        if (requester != null) {
            requester.sendMessage(Component.text(target.getName()
                    + " declined the trade request.", NamedTextColor.GRAY));
        }
        return Result.ok("Trade request declined.");
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
                .map(TradeEscrowStore.Entry::item)
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
        List<TradeEscrowStore.Entry> offer = offerOf(session, player.getUniqueId());
        if (offer.size() >= 18) return Result.fail("Trade offer is full (18 stacks).");
        TradeEscrowStore.Entry entry = escrow.hold(session.id, player.getUniqueId(), held);
        if (entry == null) {
            return Result.fail("Item could not be secured in durable escrow; nothing moved.");
        }
        offer.add(entry);
        player.getInventory().setItemInMainHand(null);
        // Persist the inventory side of the escrow handoff immediately. The
        // DB row is written first so a hard kill favors a recoverable refund
        // over permanent item loss.
        player.saveData();
        resetConfirmations(session);
        notifyBoth(session, sessionUpdate("Trade offer changed; both confirmations reset.",
                NamedTextColor.YELLOW));
        return Result.ok("Added stack to escrow. Use /idle trade view or confirm.");
    }

    public Result confirm(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        if (player.getUniqueId().equals(session.a)) session.aConfirmed = true;
        else session.bConfirmed = true;
        if (!session.aConfirmed || !session.bConfirmed) {
            notifyOther(session, player.getUniqueId(),
                    sessionUpdate(player.getName() + " confirmed the trade.",
                            NamedTextColor.GREEN));
            return Result.ok("Confirmed. Waiting for the other player.");
        }
        return settle(session);
    }

    public Result removeOffer(Player player, int index) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        List<TradeEscrowStore.Entry> offer = offerOf(session, player.getUniqueId());
        if (index < 0 || index >= offer.size()) return Result.fail("Offer changed; refresh the trade.");
        TradeEscrowStore.Entry returned = offer.get(index);
        if (!escrow.queueReturn(returned.escrowId())) {
            return Result.fail("Stack could not be released from durable escrow.");
        }
        offer.remove(index);
        deliverPending(player);
        resetConfirmations(session);
        notifyBoth(session, sessionUpdate("Trade offer changed; both confirmations reset.",
                NamedTextColor.YELLOW));
        return Result.ok("Stack removed from escrow.");
    }

    public Result cancel(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return Result.fail("You are not in a trade.");
        if (!escrow.queueTradeReturn(session.id,
                session.aItems.size() + session.bItems.size())) {
            resetConfirmations(session);
            return Result.fail("Trade could not be cancelled safely; escrow remains protected.");
        }
        close(session);
        deliverPending(Bukkit.getPlayer(session.a));
        deliverPending(Bukkit.getPlayer(session.b));
        notifyBoth(session, Component.text("Trade cancelled; escrow items were returned.",
                NamedTextColor.RED));
        return Result.ok("Trade cancelled.");
    }

    public View view(UUID player) {
        Session session = sessions.get(player);
        if (session == null) return null;
        boolean isA = player.equals(session.a);
        return new View(isA ? session.b : session.a,
                itemsOf(isA ? session.aItems : session.bItems),
                itemsOf(isA ? session.bItems : session.aItems),
                isA ? session.aConfirmed : session.bConfirmed,
                isA ? session.bConfirmed : session.aConfirmed);
    }

    public void shutdown() {
        for (Session session : new java.util.HashSet<>(sessions.values())) {
            if (escrow.queueTradeReturn(session.id,
                    session.aItems.size() + session.bItems.size())) {
                close(session);
                deliverPending(Bukkit.getPlayer(session.a));
                deliverPending(Bukkit.getPlayer(session.b));
            }
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliverPending(event.getPlayer());
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
        // Receipt and ownership transfer commit together. Actual inventory
        // delivery is replayable from PENDING_DELIVERY rows after a restart.
        boolean committed = escrow.settle(session.id, session.a, session.b, offerA, offerB,
                session.aItems.size() + session.bItems.size());
        if (!committed) {
            resetConfirmations(session);
            return Result.fail("Trade could not be committed; nothing moved.");
        }
        close(session);
        deliverPending(a);
        deliverPending(b);
        audit.log(session.a, "TRADE_COMPLETED", "{\"trade\":\"" + session.id
                + "\",\"partner\":\"" + session.b + "\"}");
        audit.log(session.b, "TRADE_COMPLETED", "{\"trade\":\"" + session.id
                + "\",\"partner\":\"" + session.a + "\"}");
        design.telemetry(session.a, "TRADE_COMPLETED", "{\"trade\":\"" + session.id + "\"}");
        design.telemetry(session.b, "TRADE_COMPLETED", "{\"trade\":\"" + session.id + "\"}");
        design.onTradeCompleted(session.a);
        design.onTradeCompleted(session.b);
        notifyBoth(session, Component.text("Protected trade completed. Receipt: " + session.id,
                NamedTextColor.GREEN));
        return Result.ok("Trade completed. Receipt: " + session.id);
    }

    private List<TradeEscrowStore.Entry> offerOf(Session session, UUID player) {
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

    private void deliverPending(Player player) {
        if (player == null || !player.isOnline()) return;
        List<TradeEscrowStore.Entry> entries;
        try {
            entries = escrow.pending(player.getUniqueId());
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Could not load pending trade delivery for "
                    + player.getUniqueId() + ": " + e.getMessage());
            return;
        }
        if (entries.isEmpty()) return;
        for (TradeEscrowStore.Entry entry : entries) {
            deliver(player, List.of(entry.item()));
        }
        // Save the granted inventory before deleting the replay journal. If
        // the process dies between these operations, replay can duplicate an
        // item but cannot silently lose one.
        player.saveData();
        if (!escrow.acknowledge(entries)) {
            plugin.getLogger().severe("Delivered " + entries.size()
                    + " trade escrow stack(s) but could not acknowledge them; "
                    + "manual review is required before the next login.");
        }
    }

    private void notifyBoth(Session session, Component message) {
        Player a = Bukkit.getPlayer(session.a);
        Player b = Bukkit.getPlayer(session.b);
        if (a != null) a.sendMessage(message);
        if (b != null) b.sendMessage(message);
    }

    private void notifyOther(Session session, UUID actor, Component message) {
        Player target = Bukkit.getPlayer(actor.equals(session.a) ? session.b : session.a);
        if (target != null) target.sendMessage(message);
    }

    /** Active-session update line carrying a [View] shortcut back to the trade. */
    private Component sessionUpdate(String text, NamedTextColor color) {
        return Component.text(text + " ", color)
                .append(CommandLinks.run("[View]", "/idle trade view"));
    }

    private List<ItemStack> itemsOf(List<TradeEscrowStore.Entry> entries) {
        return entries.stream().map(TradeEscrowStore.Entry::item)
                .map(ItemStack::clone).toList();
    }

    private String serialize(List<TradeEscrowStore.Entry> items) {
        return items.stream()
                .map(entry -> TradeEscrowStore.encode(entry.item()))
                .reduce((left, right) -> left + ";" + right).orElse("");
    }
}
