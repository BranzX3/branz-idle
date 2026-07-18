package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.Trait;
import dev.branzx.idlefarm.worker.WorkerRecord;
import dev.branzx.idlefarm.worker.WorkerStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
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

    private final IdleFarmPlugin plugin;
    private final WorkerStore workerStore;
    private final PlayerDataStore playerDataStore;
    private final NamespacedKey workerKey;

    public WorkerService(IdleFarmPlugin plugin, WorkerStore workerStore, PlayerDataStore playerDataStore) {
        this.plugin = plugin;
        this.workerStore = workerStore;
        this.playerDataStore = playerDataStore;
        this.workerKey = new NamespacedKey(plugin, "worker_uuid");
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
        data.addBalance(-cost);

        Rarity rarity = rollRarity();
        WorkerRecord record = mint(rarity);
        ItemStack item = createItem(record);
        return Result.ok("Hired " + record.getName() + " (" + rarity + ")!", item);
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

    private WorkerRecord mint(Rarity rarity) {
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

        WorkerRecord record = new WorkerRecord(UUID.randomUUID(), rarity, trait, stats,
                name, skin, 1, 0, null, WorkerRecord.STATE_ITEM);
        workerStore.insert(record);
        return record;
    }

    private String rollSkin() {
        List<String> pool = plugin.getConfig().getStringList("workers.skin-pool");
        if (pool.isEmpty()) {
            return "Steve";
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Rank-gated cosmetic: change a worker's skin (item must be re-minted by caller). */
    public Result setSkin(WorkerRecord worker, String skin) {
        worker.setSkin(skin);
        workerStore.update(worker);
        return Result.ok(worker.getName() + "'s skin changed to " + skin + ".",
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
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        // Head texture follows the worker's skin for instant recognition.
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(record.getSkin()));
        }
        meta.getPersistentDataContainer().set(workerKey, PersistentDataType.STRING,
                record.getWorkerUuid().toString());
        meta.displayName(Component.text(record.getName(), record.getRarity().color())
                .append(Component.text(" [" + record.getRarity() + "]", NamedTextColor.GRAY)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Worker Contract", NamedTextColor.DARK_GRAY));
        lore.add(Component.text("Trait: " + record.getTrait(), NamedTextColor.YELLOW));
        lore.add(Component.text("Skin: " + record.getSkin(), NamedTextColor.DARK_AQUA));
        lore.add(Component.text("Lv." + record.getLevel() + " / " + record.getRarity().levelCap(), NamedTextColor.GRAY));
        WorkerStats stats = record.getStats();
        lore.add(Component.text("DIL " + stats.diligence() + "  LCK " + stats.luck()
                + "  STA " + stats.stamina() + "  SPD " + stats.speed(), NamedTextColor.AQUA));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
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
        return workerStore.get(UUID.fromString(uuid));
    }

    // ---- fuse ----

    public int fuseCount() {
        return plugin.getConfig().getInt("workers.fuse-count", 3);
    }

    /**
     * Consumes the given same-rarity workers and mints one of the next rarity.
     * Caller must have already removed the item tokens from the inventory.
     */
    public Result fuse(List<WorkerRecord> materials) {
        if (materials.size() != fuseCount()) {
            return Result.fail("Fuse requires exactly " + fuseCount() + " workers.");
        }
        Rarity rarity = materials.get(0).getRarity();
        for (WorkerRecord material : materials) {
            if (material.getRarity() != rarity) {
                return Result.fail("All fuse materials must share the same rarity.");
            }
            if (!material.isItemForm() || !WorkerRecord.STATE_ITEM.equals(material.getState())) {
                return Result.fail(material.getName() + " is assigned to a node — eject it first.");
            }
        }
        Rarity next = rarity.next();
        if (next == null) {
            return Result.fail("Legendary workers cannot be fused further.");
        }
        for (WorkerRecord material : materials) {
            workerStore.delete(material);
        }
        WorkerRecord fused = mint(next);
        return Result.ok("Fused into " + fused.getName() + " (" + next + ")!", createItem(fused));
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
        boolean changed = false;
        while (worker.getLevel() < worker.getRarity().levelCap()
                && worker.getExp() >= expForNextLevel(worker.getLevel())) {
            worker.setExp(worker.getExp() - expForNextLevel(worker.getLevel()));
            worker.setLevel(worker.getLevel() + 1);
            allocateStatPoints(worker);
            changed = true;
        }
        if (changed) {
            workerStore.update(worker);
        }
    }

    public long expForNextLevel(int level) {
        long base = plugin.getConfig().getLong("workers.exp-per-level-base", 100);
        return base * level;
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
        // Dupe guard: the DB/cache state is authoritative, not the item.
        if (!worker.isItemForm() || !WorkerRecord.STATE_ITEM.equals(worker.getState())) {
            return Result.fail("This worker contract is stale — the worker is already assigned.");
        }
        int slots = node.getTier();
        int used = workerStore.getAssigned(node.getId()).size();
        if (used >= slots) {
            return Result.fail("No free worker slots (tier " + node.getTier() + " = " + slots + " slots).");
        }
        worker.setAssignedNodeId(node.getId());
        worker.setState(WorkerRecord.STATE_WORKING);
        workerStore.reindexAssignment(worker, null);
        return Result.ok(worker.getName() + " assigned to " + node.getType() + " node.");
    }

    public Result eject(UUID actor, WorkerRecord worker) {
        if (worker.isItemForm()) {
            return Result.fail("This worker is not assigned.");
        }
        if (WorkerRecord.STATE_EXPLORING.equals(worker.getState())) {
            return Result.fail("This worker is away exploring.");
        }
        Long previous = worker.getAssignedNodeId();
        worker.setAssignedNodeId(null);
        worker.setState(WorkerRecord.STATE_ITEM);
        workerStore.reindexAssignment(worker, previous);
        return Result.ok(worker.getName() + " ejected.", createItem(worker));
    }
}
