package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.gui.Ui;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.Rarity;
import dev.branzx.idle.worker.Trait;
import dev.branzx.idle.worker.WorkerRecord;
import dev.branzx.idle.worker.WorkerStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class WorkerService {

    public record Result(boolean success, String message, ItemStack item) {
        static Result ok(String message) {
            return new Result(true, message, null);
        }

        static Result ok(String message, ItemStack item) {
            return new Result(true, message, item);
        }

        static Result fail(String message) {
            return new Result(false, message, null);
        }
    }

    private static final String[] NAMES = {
            "Bram", "Cato", "Dov", "Edda", "Finn", "Greta", "Hale", "Ines",
            "Joro", "Kip", "Luna", "Milo", "Nia", "Odo", "Pia", "Quin",
            "Rolf", "Sena", "Tovi", "Ursa", "Vik", "Wren", "Yara", "Zed"
    };

    private final IdlePlugin plugin;
    private final WorkerStore workerStore;
    private final PlayerDataStore playerDataStore;
    private final dev.branzx.idle.storage.Database database;
    private final NamespacedKey workerKey;
    private final SkinHeadCache skinHeadCache;
    // owner -> rarity -> accumulated fail count (pity)
    private final java.util.Map<UUID, java.util.Map<Rarity, Integer>> pity =
            new java.util.concurrent.ConcurrentHashMap<>();

    public WorkerService(IdlePlugin plugin, WorkerStore workerStore, PlayerDataStore playerDataStore,
                         dev.branzx.idle.storage.Database database,
                         dev.branzx.idle.storage.NodeStore nodeStore, NodeAnchorStore anchorStore,
                         AuditService auditService, GlobalExpeditionService globalExpeditionService,
                         GameDesignService gameDesignService, SkinHeadCache skinHeadCache) {
        this(plugin, workerStore, playerDataStore, database, new NamespacedKey(plugin, "worker_uuid"),
                skinHeadCache);
        this.nodeStore = nodeStore;
        this.anchorStore = anchorStore;
        this.auditService = auditService;
        this.globalExpeditionService = globalExpeditionService;
        this.gameDesignService = gameDesignService;
    }

    /** Test seam: collaborators stay null and every use is null-guarded. */
    WorkerService(IdlePlugin plugin, WorkerStore workerStore, PlayerDataStore playerDataStore,
                  dev.branzx.idle.storage.Database database, NamespacedKey workerKey) {
        this(plugin, workerStore, playerDataStore, database, workerKey, null);
    }

    private WorkerService(IdlePlugin plugin, WorkerStore workerStore, PlayerDataStore playerDataStore,
                          dev.branzx.idle.storage.Database database, NamespacedKey workerKey,
                          SkinHeadCache skinHeadCache) {
        this.plugin = plugin;
        this.workerStore = workerStore;
        this.playerDataStore = playerDataStore;
        this.database = database;
        this.workerKey = workerKey;
        this.skinHeadCache = skinHeadCache;
    }

    private AuditService auditService;
    private NodeAnchorStore anchorStore;
    private dev.branzx.idle.storage.NodeStore nodeStore;
    private GlobalExpeditionService globalExpeditionService;
    private GameDesignService gameDesignService;

    private void audit(UUID actor, String action, String detail) {
        if (auditService != null) {
            auditService.log(actor, action, detail);
        }
    }

    public void loadPitySync() {
        try (var connection = database.getConnection();
             var select = connection.prepareStatement(
                     "SELECT owner_uuid, rarity, fails FROM idle_fuse_pity");
             var rs = select.executeQuery()) {
            while (rs.next()) {
                Rarity rarity = Rarity.fromString(rs.getString("rarity"));
                if (rarity != null) {
                    pity.computeIfAbsent(UUID.fromString(rs.getString("owner_uuid")),
                            k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(rarity, rs.getInt("fails"));
                }
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("Failed to load fuse pity: " + e.getMessage());
        }
    }

    // ---- hire (gacha) ----

    public double hireCost() {
        return plugin.getConfig().getDouble("workers.hire-cost", 250.0);
    }

    public Result hire(UUID owner) {
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null) {
            return Result.fail("Your data is still loading, try again in a moment.");
        }
        double cost = hireCost();
        if (data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }
        Rarity rarity = rollRarity();
        WorkerRecord record = createWorker(rarity);
        boolean toBag = workerStore.bagCount(owner) < bagCapacity(owner);
        if (toBag) {
            record.setOwnerUuid(owner);
            record.setState(WorkerRecord.STATE_BAG);
        }
        if (!workerStore.insertWithCost(record, data, cost)) {
            return Result.fail("Hire could not be committed; no Coins were charged.");
        }
        audit(owner, "HIRE", record.getName() + " " + rarity + " cost=" + cost);
        String message = "Hired " + record.getName() + " (" + rarity + ")!";
        return toBag ? Result.ok(message + " → Worker Bag.")
                : Result.ok(message + " (bag full → item)", createItem(record));
    }

    private Rarity rollRarity() {
        ConfigurationSection odds = plugin.getConfig().getConfigurationSection("workers.gacha-odds");
        double roll = ThreadLocalRandom.current().nextDouble() * 100.0;
        double cumulative = 0;
        for (Rarity rarity : Rarity.values()) {
            double weight = odds == null ? defaultOdds(rarity)
                    : odds.getDouble(rarity.name().toLowerCase(Locale.ROOT), defaultOdds(rarity));
            cumulative += weight;
            if (roll < cumulative) {
                return rarity;
            }
        }
        return Rarity.COMMON;
    }

    private double defaultOdds(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 55.0;
            case UNCOMMON -> 27.0;
            case RARE -> 12.0;
            case EPIC -> 5.0;
            case LEGENDARY -> 1.0;
        };
    }

    private WorkerRecord createWorker(Rarity rarity) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int min = plugin.getConfig().getInt("workers.stat-ranges." + rarity.name().toLowerCase(Locale.ROOT) + ".min",
                defaultStatMin(rarity));
        int max = plugin.getConfig().getInt("workers.stat-ranges." + rarity.name().toLowerCase(Locale.ROOT) + ".max",
                defaultStatMax(rarity));
        WorkerStats stats = new WorkerStats(
                random.nextInt(min, max + 1),
                random.nextInt(min, max + 1),
                random.nextInt(min, max + 1),
                random.nextInt(min, max + 1));
        Trait trait = Trait.values()[random.nextInt(Trait.values().length)];
        String name = NAMES[random.nextInt(NAMES.length)];
        String skin = rollSkin();

        return new WorkerRecord(UUID.randomUUID(), null, rarity, trait, stats,
                name, skin, 1, 0, null, WorkerRecord.STATE_ITEM);
    }

    /** Grants the one account-bound, fixed-stat onboarding worker. */
    public Result grantStarter(UUID owner) {
        if (gameDesignService == null) {
            return Result.fail("Starter progression is not ready.");
        }
        WorkerRecord starter = new WorkerRecord(UUID.randomUUID(), null, Rarity.COMMON,
                Trait.BALANCED, new WorkerStats(8, 8, 8, 8),
                "Pioneer", plugin.getConfig().getString("workers.starter-skin", "Steve"),
                1, 0, null, WorkerRecord.STATE_ITEM);
        workerStore.insert(starter);
        gameDesignService.markStarterWorker(owner, starter.getWorkerUuid());
        return deposit(owner, starter, "Starter Worker Pioneer joined your Worker Bag!");
    }

    // ---- virtual bag ----

    public int bagCapacity(UUID owner) {
        int base = plugin.getConfig().getInt("workers.bag.base-capacity", 54);
        int bonus = bagBonus.getOrDefault(owner, 0);
        return base + bonus;
    }

    private final java.util.Map<UUID, Integer> bagBonus = new java.util.concurrent.ConcurrentHashMap<>();

    public void loadBagBonusSync() {
        try (var connection = database.getConnection();
             var select = connection.prepareStatement(
                     "SELECT owner_uuid, bonus FROM idle_bag_cap");
             var rs = select.executeQuery()) {
            while (rs.next()) {
                bagBonus.put(UUID.fromString(rs.getString("owner_uuid")), rs.getInt("bonus"));
            }
        } catch (java.sql.SQLException e) {
            plugin.getLogger().severe("Failed to load bag caps: " + e.getMessage());
        }
    }

    public String expandBag(UUID owner) {
        PlayerData data = playerDataStore.getOnline(owner);
        double cost = plugin.getConfig().getDouble("workers.bag.expand-cost", 5000);
        int step = plugin.getConfig().getInt("workers.bag.expand-step", 9);
        if (data == null || data.getBalance() < cost) {
            return "Not enough money (need " + cost + ").";
        }
        int bonus = bagBonus.getOrDefault(owner, 0) + step;
        double balanceAfter = data.getBalance() - cost;
        boolean committed = database.executeTransaction("expand worker bag " + owner,
                connection -> {
            try (var upsert = connection.prepareStatement(
                         "REPLACE INTO idle_bag_cap (owner_uuid, bonus) VALUES (?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setInt(2, bonus);
                upsert.executeUpdate();
            }
            dev.branzx.idle.storage.CoinSql.debit(connection, owner, Math.round(cost));
        });
        if (!committed) return "Expansion could not be settled; no Coins were charged.";
        data.addBalance(-cost);
        bagBonus.put(owner, bonus);
        return null;
    }

    /**
     * Sends a freshly minted worker to the owner's bag; if the bag is full it
     * overflows into inventory as an item (dropped at feet if inventory full).
     */
    public Result deposit(UUID owner, WorkerRecord record, String successMessage) {
        if (workerStore.bagCount(owner) < bagCapacity(owner)) {
            workerStore.moveToBag(record, owner);
            return Result.ok(successMessage + " → Worker Bag.");
        }
        // Bag full: hand out an item.
        workerStore.moveToItem(record);
        return Result.ok(successMessage + " (bag full → item)", createItem(record));
    }

    /** Deposit an existing item-form worker (e.g. from inventory) into the bag. */
    public String depositItem(UUID owner, WorkerRecord record) {
        if (!WorkerRecord.STATE_ITEM.equals(record.getState())) {
            return "That worker is not an item.";
        }
        if (workerStore.bagCount(owner) >= bagCapacity(owner)) {
            return "Your worker bag is full.";
        }
        workerStore.moveToBag(record, owner);
        return null;
    }

    /** Withdraw a bag worker to item form; returns the item or null on error. */
    public ItemStack withdraw(UUID owner, WorkerRecord record) {
        if (gameDesignService != null && (gameDesignService.isStarterWorker(record.getWorkerUuid())
                || gameDesignService.isWorkerLocked(record.getWorkerUuid()))) {
            return null;
        }
        if (!record.isInBag() || !owner.equals(record.getOwnerUuid())) {
            return null;
        }
        workerStore.moveToItem(record);
        return createItem(record);
    }

    private String rollSkin() {
        List<String> pool = plugin.getConfig().getStringList("workers.skin-pool");
        if (pool.isEmpty()) {
            return "Steve";
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Change a worker's skin. Returns a refreshed item if it is item-form. */
    public Result setSkin(WorkerRecord worker, String skin) {
        if (skin == null || skin.isBlank() || skin.length() > 16
                || !skin.matches("[A-Za-z0-9_]+")) {
            return Result.fail("Invalid skin name (use a player username).");
        }
        worker.setSkin(skin);
        workerStore.update(worker);
        return Result.ok(worker.getName() + "'s skin changed to " + skin + ".",
                worker.isItemForm() ? createItem(worker) : null);
    }

    /** Rename a worker (free). Returns a refreshed item if it is item-form. */
    public Result rename(WorkerRecord worker, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank() || trimmed.length() > 24) {
            return Result.fail("Name must be 1–24 characters.");
        }
        // Strip section/formatting chars; keep it plain.
        trimmed = trimmed.replaceAll("[§&]", "");
        worker.setName(trimmed);
        workerStore.update(worker);
        return Result.ok("Worker renamed to " + trimmed + ".",
                worker.isItemForm() ? createItem(worker) : null);
    }

    private int defaultStatMin(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 1;
            case UNCOMMON -> 5;
            case RARE -> 10;
            case EPIC -> 18;
            case LEGENDARY -> 28;
        };
    }

    private int defaultStatMax(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 10;
            case UNCOMMON -> 18;
            case RARE -> 28;
            case EPIC -> 40;
            case LEGENDARY -> 55;
        };
    }

    // ---- item token ----

    public ItemStack createItem(WorkerRecord record) {
        ItemStack item = skinHeadCache == null
                ? new ItemStack(Material.PLAYER_HEAD)
                : skinHeadCache.createHead(record.getSkin());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(workerKey, PersistentDataType.STRING,
                record.getWorkerUuid().toString());
        meta.displayName(Component.text("✦ ", record.getRarity().color())
                .append(Component.text(record.getName(), record.getRarity().color(),
                        net.kyori.adventure.text.format.TextDecoration.BOLD))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(workerLore(record));
        item.setItemMeta(meta);
        return item;
    }

    /** Shared fancy lore used by the contract item and the node-slot icon. */
    public List<Component> workerLore(WorkerRecord record) {
        WorkerStats stats = record.getStats();
        long needed = expForNextLevel(record.getLevel());
        double levelFrac = needed <= 0 ? 1.0 : Math.min(1.0, record.getExp() / (double) needed);
        boolean capped = record.getLevel() >= record.getRarity().levelCap();

        List<Component> lore = new ArrayList<>();
        lore.add(Ui.stars(record.getRarity())
                .append(Ui.line("  " + record.getRarity(), record.getRarity().color())));
        lore.add(Ui.divider());
        lore.add(Ui.line("Role  " + role(record), NamedTextColor.AQUA));
        lore.add(Ui.line(String.format("+%.1f%% passive quantity", stats.diligence() / 100.0 * 100),
                NamedTextColor.GRAY));
        lore.add(Ui.line(String.format("+%.1f%% research power", stats.stamina() / 100.0 * 100),
                NamedTextColor.GRAY));
        lore.add(Ui.line(String.format("-%.1f%% expedition time", Math.min(50, stats.speed() * 0.25)),
                NamedTextColor.GRAY));
        lore.add(Ui.line(String.format("+%.1f%% Great shift", stats.luck() * 0.2),
                NamedTextColor.GRAY));
        lore.add(Ui.divider());
        lore.add(Ui.line("Trait ", NamedTextColor.GRAY)
                .append(Ui.line("✧ " + Ui.pretty(record.getTrait().name()), NamedTextColor.YELLOW)));
        lore.add(Ui.line("Skin  ", NamedTextColor.GRAY)
                .append(Ui.line(record.getSkin(), NamedTextColor.DARK_AQUA)));
        lore.add(Ui.divider());
        lore.add(Ui.stat("⛏", "Diligence", stats.diligence(), 60, NamedTextColor.GOLD));
        lore.add(Ui.stat("☘", "Luck", stats.luck(), 60, NamedTextColor.GREEN));
        lore.add(Ui.stat("❤", "Stamina", stats.stamina(), 60, NamedTextColor.RED));
        lore.add(Ui.stat("»", "Speed", stats.speed(), 60, NamedTextColor.AQUA));
        lore.add(Ui.divider());
        if (capped) {
            lore.add(Ui.line("Lv." + record.getLevel() + "  MAX", NamedTextColor.GOLD));
        } else {
            lore.add(Ui.bar("Lv." + record.getLevel(), levelFrac, NamedTextColor.GREEN,
                    Ui.num(record.getExp()) + "/" + Ui.num(needed) + " xp"));
        }
        lore.add(Ui.line("Worker Contract", NamedTextColor.DARK_GRAY));
        return lore;
    }

    public String role(WorkerRecord record) {
        WorkerStats stats = record.getStats();
        int max = Math.max(Math.max(stats.diligence(), stats.luck()),
                Math.max(stats.stamina(), stats.speed()));
        int min = Math.min(Math.min(stats.diligence(), stats.luck()),
                Math.min(stats.stamina(), stats.speed()));
        if (record.getTrait() == Trait.BALANCED && max - min <= 5) return "Leader";
        if (max == stats.diligence()) return "Producer";
        if (max == stats.luck()) return "Scout";
        if (max == stats.stamina()) return "Researcher";
        return "Runner";
    }

    /** Returns the worker bound to this item, or null if it is not a worker token. */
    public WorkerRecord fromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String uuid = item.getItemMeta().getPersistentDataContainer()
                .get(workerKey, PersistentDataType.STRING);
        if (uuid == null) {
            return null;
        }
        try {
            return workerStore.get(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ---- fuse (2-worker gacha with per-rarity pity) ----

    /** Two same-rarity workers of any level. */
    public int fuseCount() {
        return 2;
    }

    public int pityCount(UUID owner, Rarity rarity) {
        var byRarity = pity.get(owner);
        return byRarity == null ? 0 : byRarity.getOrDefault(rarity, 0);
    }

    /**
     * Effective success chance (0..1) including accumulated pity. Reaches a
     * guaranteed 1.0 at the configured hard-pity fail count.
     */
    public double fuseChance(UUID owner, Rarity rarity) {
        double base = plugin.getConfig().getDouble(
                "workers.fuse.base-chance." + rarity.name().toLowerCase(Locale.ROOT), defaultChance(rarity));
        double perFail = plugin.getConfig().getDouble("workers.fuse.pity-per-fail", 0.1);
        return Math.min(1.0, base + pityCount(owner, rarity) * perFail);
    }

    private double defaultChance(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> 0.6;
            case UNCOMMON -> 0.4;
            case RARE -> 0.25;
            case EPIC -> 0.12;
            case LEGENDARY -> 0.0; // cannot fuse past legendary
        };
    }

    /**
     * Fuses exactly two same-rarity workers. Rolls {@link #fuseChance}:
     * success consumes both materials and mints the next rarity, resetting
     * pity; failure consumes only the duplicate (slot 2), protects the base
     * worker and increments pity so the next attempt is likelier. Caller
     * removes the consumed item tokens from inventory.
     */
    public Result fuse(UUID owner, List<WorkerRecord> materials) {
        if (materials.size() != 2) {
            return Result.fail("Fuse combines exactly 2 workers of the same rarity.");
        }
        if (materials.get(0).getWorkerUuid().equals(materials.get(1).getWorkerUuid())) {
            return Result.fail("A worker cannot fill both fuse material slots.");
        }
        Rarity rarity = materials.get(0).getRarity();
        for (WorkerRecord material : materials) {
            if (material.isInBag() && !owner.equals(material.getOwnerUuid())) {
                return Result.fail("You do not own " + material.getName() + ".");
            }
            if (gameDesignService != null && gameDesignService.isStarterWorker(material.getWorkerUuid())) {
                return Result.fail("The account-bound Starter Worker cannot be fused.");
            }
            if (gameDesignService != null && gameDesignService.isWorkerLocked(material.getWorkerUuid())) {
                return Result.fail(material.getName() + " is favorite/locked.");
            }
            if (material.getRarity() != rarity) {
                return Result.fail("Both workers must share the same rarity.");
            }
            // Bag or item form is fine; assigned workers must be ejected first.
            boolean available = WorkerRecord.STATE_ITEM.equals(material.getState())
                    || WorkerRecord.STATE_BAG.equals(material.getState());
            if (!available) {
                return Result.fail(material.getName() + " is assigned to a node — eject it first.");
            }
        }
        Rarity next = rarity.next();
        if (next == null) {
            return Result.fail("Legendary workers cannot be fused further.");
        }

        double chance = fuseChance(owner, rarity);
        boolean success = ThreadLocalRandom.current().nextDouble() < chance;
        long trainingNotes = Math.round(materials.stream().mapToLong(WorkerRecord::getExp).sum() * 0.25);

        if (success) {
            setPity(owner, rarity, 0);
            WorkerRecord fused = createWorker(next);
            // Both consumed rows and the minted row settle in one transaction
            // so a restart can never keep the materials and the result.
            workerStore.fuseSettle(materials, fused);
            if (gameDesignService != null) {
                gameDesignService.addTrainingNotes(owner, trainingNotes);
                gameDesignService.onWorkerFused(owner);
            }
            audit(owner, "FUSE", rarity + " SUCCESS -> " + next + " chance=" + Math.round(chance * 100) + "%");
            return deposit(owner, fused, "SUCCESS! Fused into " + fused.getName() + " (" + next + ")!");
        } else {
            // Protect the selected base worker; a failed fuse consumes only
            // the duplicate and advances pity.
            workerStore.fuseSettle(List.of(materials.get(1)), null);
            if (gameDesignService != null) {
                gameDesignService.addTrainingNotes(owner,
                        Math.round(materials.get(1).getExp() * 0.25));
            }
            int fails = pityCount(owner, rarity) + 1;
            setPity(owner, rarity, fails);
            double nextChance = fuseChance(owner, rarity);
            audit(owner, "FUSE", rarity + " FAIL pity=" + fails + " chance=" + Math.round(chance * 100) + "%");
            return Result.fail("Fuse failed — protected base survived. Pity +1 (next "
                    + rarity + " fuse: " + Math.round(nextChance * 100) + "% success).");
        }
    }

    private void setPity(UUID owner, Rarity rarity, int fails) {
        pity.computeIfAbsent(owner, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(rarity, fails);
        database.submitWrite(() -> {
            try (var connection = database.getConnection();
                 var upsert = connection.prepareStatement(
                         "REPLACE INTO idle_fuse_pity (owner_uuid, rarity, fails) VALUES (?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setString(2, rarity.name());
                upsert.setInt(3, fails);
                upsert.executeUpdate();
            } catch (java.sql.SQLException e) {
                plugin.getLogger().severe("Failed to persist fuse pity: " + e.getMessage());
            }
        });
    }

    // ---- growth ----

    /**
     * Grants EXP (already Stamina-scaled by the caller) and applies level-ups:
     * each level grants stat points allocated randomly, weighted toward the
     * worker's Trait-favored stat. Level cap comes from rarity.
     */
    public void grantExp(WorkerRecord worker, long amount) {
        if (amount <= 0 || worker.getLevel() >= worker.getRarity().levelCap()) {
            return;
        }
        worker.setExp(worker.getExp() + amount);
        while (worker.getLevel() < worker.getRarity().levelCap()
                && worker.getExp() >= expForNextLevel(worker.getLevel())) {
            worker.setExp(worker.getExp() - expForNextLevel(worker.getLevel()));
            worker.setLevel(worker.getLevel() + 1);
            allocateStatPoints(worker);
        }
        // EXP below the next level is progression too. Persist every credited
        // grant so a restart cannot roll a worker back to its previous value.
        workerStore.update(worker);
    }

    public long expForNextLevel(int level) {
        long base = plugin.getConfig().getLong("workers.exp-per-level-base", 100);
        return base * level;
    }

    public Result applyTrainingNotes(UUID owner, WorkerRecord worker, long amount) {
        if (gameDesignService == null) return Result.fail("Training Notes are not available.");
        long used = gameDesignService.takeTrainingNotes(owner, amount);
        if (used <= 0) return Result.fail("No Training Notes available.");
        grantExp(worker, used);
        return Result.ok("Applied " + used + " Training Notes to " + worker.getName() + ".");
    }

    private void allocateStatPoints(WorkerRecord worker) {
        int points = plugin.getConfig().getInt("workers.points-per-level", 3);
        double favoredChance = plugin.getConfig().getDouble("workers.trait-favored-chance", 0.5);
        String favored = worker.getTrait().favoredStat();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int dDil = 0, dLck = 0, dSta = 0, dSpd = 0;
        for (int i = 0; i < points; i++) {
            String stat;
            if (favored != null && random.nextDouble() < favoredChance) {
                stat = favored;
            } else {
                stat = switch (random.nextInt(4)) {
                    case 0 -> "Diligence";
                    case 1 -> "Luck";
                    case 2 -> "Stamina";
                    default -> "Speed";
                };
            }
            switch (stat) {
                case "Diligence" -> dDil++;
                case "Luck" -> dLck++;
                case "Stamina" -> dSta++;
                default -> dSpd++;
            }
        }
        worker.setStats(worker.getStats().plus(dDil, dLck, dSta, dSpd));
    }

    // ---- assign / eject ----

    public Result assign(UUID actor, WorkerRecord worker, NodeRecord node) {
        if (!node.getType().isProduction()) {
            return Result.fail("Workers can only be assigned to production nodes.");
        }
        if (worker.isInBag() && !actor.equals(worker.getOwnerUuid())) {
            return Result.fail("That Worker Bag contract belongs to another player.");
        }
        if (gameDesignService != null && gameDesignService.isStarterWorker(worker.getWorkerUuid())
                && !actor.equals(worker.getOwnerUuid())) {
            return Result.fail("The account-bound Starter Worker cannot change owners.");
        }
        // Dupe guard: must be available (bag or item), not already assigned.
        boolean available = WorkerRecord.STATE_ITEM.equals(worker.getState())
                || WorkerRecord.STATE_BAG.equals(worker.getState());
        if (!available) {
            return Result.fail("That worker is already assigned.");
        }
        int slots = node.getTier();
        int used = workerStore.getAssigned(node.getId()).size();
        if (used >= slots) {
            return Result.fail("No free worker slots (tier " + node.getTier() + " = " + slots + " slots).");
        }
        boolean wasBag = worker.isInBag();
        if (wasBag) {
            // Remove from the bag index cleanly before assigning.
            workerStore.moveToItem(worker);
        }
        // A worker starts earning from the assignment moment. Resetting the
        // node anchor also guards against stale persisted idle timestamps.
        node.setLastTickAt(System.currentTimeMillis());
        if (nodeStore != null) {
            nodeStore.updateProduction(node);
        }
        worker.setAssignedNodeId(node.getId());
        worker.setState(WorkerRecord.STATE_WORKING);
        workerStore.reindexAssignment(worker, null);
        if (gameDesignService != null) {
            gameDesignService.onWorkerAssigned(node, workerStore.getAssigned(node.getId()).size());
        }
        return Result.ok(worker.getName() + " assigned to " + node.getType() + " node.");
    }

    /**
     * Ejects an assigned worker back to the owner's bag (overflow to item if
     * the bag is full). Returns an item only on overflow.
     */
    public Result eject(UUID actor, WorkerRecord worker) {
        if (worker.getAssignedNodeId() == null) {
            return Result.fail("This worker is not assigned.");
        }
        // The GUI already filters, but ownership is authoritative here: only
        // the owner of the assigned node may recall its crew into their bag.
        if (nodeStore != null) {
            NodeRecord node = nodeStore.getById(worker.getAssignedNodeId());
            if (node != null && !node.getOwnerUuid().equals(actor)) {
                return Result.fail("That worker serves another player's node.");
            }
        }
        if (globalExpeditionService != null
                && globalExpeditionService.isCommitted(worker.getWorkerUuid())) {
            return Result.fail("This worker is committed to the Global Expedition.");
        }
        if (WorkerRecord.STATE_EXPLORING.equals(worker.getState())) {
            return Result.fail("This worker is away exploring.");
        }
        Long previous = worker.getAssignedNodeId();
        worker.setAssignedNodeId(null);
        worker.setState(WorkerRecord.STATE_ITEM);
        workerStore.reindexAssignment(worker, previous);
        // Custom spawn/work override is cleared with the worker (spec §4.5).
        if (anchorStore != null) {
            anchorStore.clearWorker(worker.getWorkerUuid());
        }
        // Try to settle it into the actor's bag; overflow becomes an item.
        if (workerStore.bagCount(actor) < bagCapacity(actor)) {
            workerStore.moveToBag(worker, actor);
            return Result.ok(worker.getName() + " ejected → Worker Bag.");
        }
        return Result.ok(worker.getName() + " ejected (bag full → item).", createItem(worker));
    }
}
