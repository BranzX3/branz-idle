package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.node.NodeRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual per-player central storage. Node collect-all routes here rather
 * than to inventory; withdraw pulls back to inventory. All maps cached in
 * memory; writes go through the ordered async queue.
 *
 * <p><b>Two pools, one shelf.</b> The production engine already splits output
 * into a discovery lane and a bulk lane with separate node buffers, and the
 * two differ by orders of magnitude: a midgame mining node produces tens of
 * thousands of commons per day against a discovery lane measured in tens per
 * hour. A single capacity counter therefore lets commons crowd out rare finds
 * and makes storage impossible to price (docs/BALANCE_BIBLE.md §3). Capacity
 * is accounted per pool:</p>
 *
 * <ul>
 *   <li><b>Vault</b> — discovery items. Small and precious; this is the
 *       capacity stored per owner and raised by the expansion purchase.</li>
 *   <li><b>Silo</b> — bulk-lane commons. Six-figure, derived from the number
 *       of expansions bought so one purchase grows the whole warehouse.</li>
 * </ul>
 *
 * <p>Reads stay unified: {@link #getContents} returns both pools merged, so
 * commissions, projects and crafting keep looking materials up by name.</p>
 */
public final class WarehouseService {

    /**
     * Immutable durable representation used by cross-aggregate transactions
     * such as Exploration loot settlement.
     */
    public record Snapshot(UUID owner, int capacity, String serializedContents) {
    }

    /** Shipped design values; see config.yml {@code warehouse.silo}. */
    private static final int DEFAULT_SILO_CAPACITY = 120_000;
    private static final int DEFAULT_SILO_STEP = 20_000;

    private final IdlePlugin plugin;
    private final Database database;
    private final ProgressionScale scale;
    // owner -> (MATERIAL -> count), insertion-ordered for stable paging
    private final Map<UUID, Map<String, Integer>> contents = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> capacities = new ConcurrentHashMap<>();

    public WarehouseService(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.scale = new ProgressionScale(plugin);
    }

    /** True when the material belongs to the bulk lane and lives in the Silo. */
    public boolean isBulkCommon(String material) {
        return scale.allBulkCommons().contains(material.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Capacity is Idle's own game design and stays in {@code idle_warehouse};
     * the stored materials live in the shared {@code wallet_warehouse}, one row
     * each, so another backend can draw on them. A legacy serialised inventory
     * is migrated into rows the first time it is seen.
     */
    public void loadAllSync() {
        try (Connection connection = database.getConnection()) {
            Map<UUID, String> legacy = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, capacity, content_json FROM idle_warehouse");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                    capacities.put(owner, rs.getInt("capacity"));
                    String blob = rs.getString("content_json");
                    if (blob != null && !blob.isBlank()) {
                        legacy.put(owner, blob);
                    }
                }
            }
            migrateLegacyContents(connection, legacy);

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, item_key, amount FROM wallet_warehouse WHERE amount > 0 "
                            + "ORDER BY owner_uuid, item_key");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                    contents.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                            .put(rs.getString("item_key"), rs.getInt("amount"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load warehouses: " + e.getMessage());
        }
    }

    /**
     * One-time move of a serialised inventory into per-material rows. Owners
     * that already have rows are left alone — their blob is simply stale — so
     * this is safe to run on every boot.
     */
    private void migrateLegacyContents(Connection connection, Map<UUID, String> legacy)
            throws SQLException {
        int moved = 0;
        for (Map.Entry<UUID, String> entry : legacy.entrySet()) {
            UUID owner = entry.getKey();
            boolean alreadyMigrated;
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM wallet_warehouse WHERE owner_uuid = ?")) {
                check.setString(1, owner.toString());
                try (ResultSet rs = check.executeQuery()) {
                    alreadyMigrated = rs.next();
                }
            }
            if (!alreadyMigrated) {
                applyDeltas(connection, owner, deserialize(entry.getValue()));
                moved++;
            }
            try (PreparedStatement clear = connection.prepareStatement(
                    "UPDATE idle_warehouse SET content_json = '' WHERE owner_uuid = ?")) {
                clear.setString(1, owner.toString());
                clear.executeUpdate();
            }
        }
        if (moved > 0) {
            plugin.getLogger().info("Migrated " + moved + " warehouse(s) into wallet_warehouse.");
        }
    }

    /**
     * Applies signed per-material deltas as relative writes on the caller's
     * connection. Never writes a whole inventory: the row is shared with other
     * backends, so only the change this server made may be sent. A debit
     * carries its floor in the WHERE clause and throws rather than going
     * negative, rolling the caller's transaction back with it.
     */
    public void applyDeltas(Connection connection, UUID owner, Map<String, Integer> deltas)
            throws SQLException {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        String upsertSql = database.isSqlite()
                ? "INSERT INTO wallet_warehouse (owner_uuid, item_key, amount) VALUES (?, ?, ?) "
                        + "ON CONFLICT(owner_uuid, item_key) DO UPDATE SET amount = amount + ?"
                : "INSERT INTO wallet_warehouse (owner_uuid, item_key, amount) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE amount = amount + ?";
        for (Map.Entry<String, Integer> entry : deltas.entrySet()) {
            int delta = entry.getValue() == null ? 0 : entry.getValue();
            String key = entry.getKey().toUpperCase(java.util.Locale.ROOT);
            if (delta > 0) {
                try (PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
                    upsert.setString(1, owner.toString());
                    upsert.setString(2, key);
                    upsert.setInt(3, delta);
                    upsert.setInt(4, delta);
                    upsert.executeUpdate();
                }
            } else if (delta < 0) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE wallet_warehouse SET amount = amount - ? "
                                + "WHERE owner_uuid = ? AND item_key = ? AND amount >= ?")) {
                    update.setInt(1, -delta);
                    update.setString(2, owner.toString());
                    update.setString(3, key);
                    update.setInt(4, -delta);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Not enough " + key + " stored for " + owner);
                    }
                }
            }
        }
    }

    /** Queues a relative warehouse change behind preceding writes. */
    private void persistDeltas(UUID owner, Map<String, Integer> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                applyDeltas(connection, owner, deltas);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist warehouse for " + owner
                        + ": " + e.getMessage());
            }
        });
    }

    /**
     * Difference between two runtime snapshots — what this server changed —
     * so a persist can be expressed as deltas instead of a whole inventory.
     */
    private static Map<String, Integer> diff(Snapshot before, Snapshot after) {
        Map<String, Integer> from = parse(before.serializedContents());
        Map<String, Integer> to = parse(after.serializedContents());
        Map<String, Integer> deltas = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : to.entrySet()) {
            int delta = entry.getValue() - from.getOrDefault(entry.getKey(), 0);
            if (delta != 0) {
                deltas.put(entry.getKey(), delta);
            }
        }
        for (Map.Entry<String, Integer> entry : from.entrySet()) {
            if (!to.containsKey(entry.getKey())) {
                deltas.put(entry.getKey(), -entry.getValue());
            }
        }
        return deltas;
    }

    private static Map<String, Integer> parse(String serialized) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (serialized == null || serialized.isBlank()) {
            return map;
        }
        for (String part : serialized.split(";")) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                map.put(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // A malformed pair is skipped rather than losing the rest.
            }
        }
        return map;
    }

    /** Vault (discovery) capacity: the stored, purchasable number. */
    public int getCapacity(UUID owner) {
        return capacities.computeIfAbsent(owner,
                k -> plugin.getConfig().getInt("warehouse.base-capacity", 2000));
    }

    public int vaultCapacity(UUID owner) {
        return getCapacity(owner);
    }

    /**
     * Silo capacity is derived, not stored: base plus one silo step per
     * expansion already bought. One purchase therefore grows both pools, and
     * the Silo keeps pace with a bulk lane that scales with the crew.
     */
    public int siloCapacity(UUID owner) {
        int vaultBase = plugin.getConfig().getInt("warehouse.base-capacity", 2000);
        int vaultStep = Math.max(1, plugin.getConfig().getInt("warehouse.expand-step", 1000));
        // Bukkit never adds a new section to an existing config.yml, so a
        // server that predates the Silo has no value here. Zero would silently
        // discard every common the bulk lane produces, so a non-positive
        // setting falls back to the shipped design value — the same rule the
        // bulk lane itself uses for a missing rate (ProgressionScale).
        int siloBase = positiveOr(
                plugin.getConfig().getInt("warehouse.silo.base-capacity", DEFAULT_SILO_CAPACITY),
                DEFAULT_SILO_CAPACITY);
        int siloStep = positiveOr(
                plugin.getConfig().getInt("warehouse.silo.expand-step", DEFAULT_SILO_STEP),
                DEFAULT_SILO_STEP);
        int expansions = Math.max(0, (vaultCapacity(owner) - vaultBase) / vaultStep);
        return siloBase + expansions * siloStep;
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public int capacityFor(UUID owner, String material) {
        return isBulkCommon(material) ? siloCapacity(owner) : vaultCapacity(owner);
    }

    public Map<String, Integer> getContents(UUID owner) {
        return Map.copyOf(mutableContents(owner));
    }

    private Map<String, Integer> mutableContents(UUID owner) {
        return contents.computeIfAbsent(owner, k -> new LinkedHashMap<>());
    }

    public int total(UUID owner) {
        return mutableContents(owner).values().stream().mapToInt(Integer::intValue).sum();
    }

    public int siloTotal(UUID owner) {
        return poolTotal(owner, true);
    }

    public int vaultTotal(UUID owner) {
        return poolTotal(owner, false);
    }

    private int poolTotal(UUID owner, boolean bulk) {
        int sum = 0;
        for (Map.Entry<String, Integer> entry : mutableContents(owner).entrySet()) {
            if (isBulkCommon(entry.getKey()) == bulk) {
                sum += entry.getValue();
            }
        }
        return sum;
    }

    /**
     * Free space in the Vault. Exploration loot and other discovery rewards
     * land here, so the existing callers keep their meaning.
     */
    public int freeSpace(UUID owner) {
        return Math.max(0, vaultCapacity(owner) - vaultTotal(owner));
    }

    public int siloFreeSpace(UUID owner) {
        return Math.max(0, siloCapacity(owner) - siloTotal(owner));
    }

    /** Free space in whichever pool this material belongs to. */
    public int freeSpaceFor(UUID owner, String material) {
        return isBulkCommon(material) ? siloFreeSpace(owner) : freeSpace(owner);
    }

    /**
     * Deposits up to {@code amount}, capped by free space. Returns the number
     * actually stored (caller handles the remainder/overflow).
     */
    public int deposit(UUID owner, String material, int amount) {
        int stored = Math.min(amount, freeSpaceFor(owner, material));
        if (stored > 0) {
            String key = material.toUpperCase(java.util.Locale.ROOT);
            mutableContents(owner).merge(key, stored, Integer::sum);
            persistDeltas(owner, Map.of(key, stored));
        }
        return stored;
    }

    /**
     * Atomically deposits an entire loot bundle into the in-memory warehouse.
     * Returns false without changing anything when the bundle does not fit.
     */
    public boolean depositAll(UUID owner, Map<String, Integer> items) {
        Snapshot before = snapshot(owner);
        Snapshot snapshot = prepareDepositAll(owner, items);
        if (snapshot == null) {
            return false;
        }
        if (!items.isEmpty()) {
            persistDeltas(owner, diff(before, snapshot));
        }
        return true;
    }

    /**
     * Applies an all-or-nothing bundle to the runtime cache without scheduling
     * a standalone write. The caller must persist the returned snapshot,
     * normally as part of a transaction that settles the source reward too.
     */
    public Snapshot prepareDepositAll(UUID owner, Map<String, Integer> items) {
        // All-or-nothing is judged per pool: a bundle of commons must not be
        // rejected because the Vault is full, and vice versa.
        long silo = 0;
        long vault = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            if (amount <= 0) {
                continue;
            }
            if (isBulkCommon(entry.getKey())) {
                silo += amount;
            } else {
                vault += amount;
            }
        }
        if (vault > freeSpace(owner) || silo > siloFreeSpace(owner)) {
            return null;
        }
        Map<String, Integer> warehouse = mutableContents(owner);
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            if (amount > 0) {
                warehouse.merge(entry.getKey().toUpperCase(java.util.Locale.ROOT), amount, Integer::sum);
            }
        }
        return snapshot(owner);
    }

    public Snapshot snapshot(UUID owner) {
        return new Snapshot(owner, getCapacity(owner), serialize(mutableContents(owner)));
    }

    /**
     * Restores a previously captured runtime snapshot after a blocking
     * cross-aggregate transaction is rejected.
     */
    public void restore(Snapshot snapshot) {
        capacities.put(snapshot.owner(), snapshot.capacity());
        contents.put(snapshot.owner(), deserialize(snapshot.serializedContents()));
    }

    /**
     * Atomically moves as much of a node buffer as fits. The cache changes on
     * the main thread and the two durable rows are committed in one ordered DB
     * transaction, preventing restart-time loss or duplication.
     */
    public int collectNode(NodeRecord node) {
        return collectNode(node, null);
    }

    /**
     * As {@link #collectNode(NodeRecord)}, but also records the per-material
     * amounts actually moved into {@code movedOut} (keys upper-cased) when it
     * is non-null, so callers can render a trip report without a racy diff.
     */
    public int collectNode(NodeRecord node, Map<String, Integer> movedOut) {
        UUID owner = node.getOwnerUuid();
        Map<String, Integer> warehouse = mutableContents(owner);
        // Kept independently of movedOut so the rollback path never depends on
        // a caller passing (or reusing) a report map.
        Map<String, Integer> moves = new LinkedHashMap<>();
        int moved = 0;
        synchronized (node) {
            // Each pool spends its own budget, so a full Silo can never keep a
            // rare discovery find from landing (and the reverse).
            int vaultSpace = freeSpace(owner);
            int siloSpace = siloFreeSpace(owner);
            for (Map<String, Integer> buffer : List.of(node.getStorage(), node.getBulkStorage())) {
                for (Map.Entry<String, Integer> entry : List.copyOf(buffer.entrySet())) {
                    String key = entry.getKey().toUpperCase(java.util.Locale.ROOT);
                    boolean bulk = isBulkCommon(key);
                    int space = bulk ? siloSpace : vaultSpace;
                    if (space <= 0) continue;
                    int amount = Math.min(space, entry.getValue());
                    if (amount <= 0) continue;
                    warehouse.merge(key, amount, Integer::sum);
                    moves.merge(key, amount, Integer::sum);
                    if (movedOut != null) movedOut.merge(key, amount, Integer::sum);
                    moved += amount;
                    if (bulk) siloSpace -= amount;
                    else vaultSpace -= amount;
                    if (amount == entry.getValue()) buffer.remove(entry.getKey());
                    else buffer.put(entry.getKey(), entry.getValue() - amount);
                }
            }
            if (moved > 0) node.setState("ACTIVE");
        }
        if (moved <= 0) return 0;
        String nodeJson = node.serializeStorage();
        String bulkJson = node.serializeBulkStorage();
        String stateBefore = node.getState();
        database.submitWrite(() -> {
            Connection connection = null;
            try {
                connection = database.getConnection();
                connection.setAutoCommit(false);
                // Only what this collection moved — the shared row may have
                // changed underneath us on another backend.
                applyDeltas(connection, owner, moves);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE idle_nodes SET storage_json = ?, bulk_storage_json = ?, "
                                + "state = ? WHERE id = ?")) {
                    update.setString(1, nodeJson);
                    update.setString(2, bulkJson);
                    update.setString(3, node.getState());
                    update.setLong(4, node.getId());
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().severe("Atomic node collection failed: " + e.getMessage());
                rollback(connection);
                // Both rows rolled back, so the caches have to follow. Without
                // this the buffer stays empty while the durable row still holds
                // the items, and the next Warehouse write — which serialises the
                // whole map — would persist them a second time.
                revertCollection(node, owner, moves, stateBefore);
            } finally {
                close(connection);
            }
        });
        return moved;
    }

    private void rollback(Connection connection) {
        if (connection == null) return;
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The pooled connection is closing; nothing was committed.
        }
    }

    private void close(Connection connection) {
        if (connection == null) return;
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // The pooled connection is closing immediately.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already returned to the pool.
        }
    }

    /**
     * Undoes a rejected collection by moving each amount back into the buffer
     * it came from. The amounts are re-added rather than the maps replaced,
     * because production keeps ticking on the main thread while this runs on
     * the database writer.
     */
    private void revertCollection(NodeRecord node, UUID owner,
                                  Map<String, Integer> moves, String stateBefore) {
        Map<String, Integer> warehouse = mutableContents(owner);
        synchronized (node) {
            for (Map.Entry<String, Integer> entry : moves.entrySet()) {
                String key = entry.getKey();
                int amount = entry.getValue();
                Map<String, Integer> buffer =
                        isBulkCommon(key) ? node.getBulkStorage() : node.getStorage();
                buffer.merge(key, amount, Integer::sum);
                warehouse.computeIfPresent(key,
                        (ignored, have) -> have - amount <= 0 ? null : have - amount);
            }
            node.setState(stateBefore);
        }
    }

    /** Removes up to {@code amount}; returns the number actually removed. */
    public int withdraw(UUID owner, String material, int amount) {
        int removed = prepareWithdraw(owner, material, amount);
        if (removed > 0) {
            persistDeltas(owner, Map.of(material.toUpperCase(java.util.Locale.ROOT), -removed));
        }
        return removed;
    }

    /**
     * Removes up to {@code amount} from the runtime cache without scheduling a
     * standalone write. The caller must persist {@link #snapshot} inside the
     * same transaction that settles whatever consumed the items.
     */
    public int prepareWithdraw(UUID owner, String material, int amount) {
        Map<String, Integer> map = mutableContents(owner);
        String key = material.toUpperCase(java.util.Locale.ROOT);
        int have = map.getOrDefault(key, 0);
        int removed = Math.min(have, amount);
        if (removed > 0) {
            if (removed == have) {
                map.remove(key);
            } else {
                map.put(key, have - removed);
            }
        }
        return removed;
    }

    /**
     * Persists a warehouse change on the caller's transaction connection, as
     * the difference between the snapshot taken before the change and the one
     * after it. Sending the difference rather than the whole inventory is what
     * lets another backend move the same warehouse concurrently.
     */
    public void write(Connection connection, Snapshot before, Snapshot after) throws SQLException {
        applyDeltas(connection, after.owner(), diff(before, after));
    }

    public boolean expandCapacity(UUID owner, PlayerData player) {
        int step = plugin.getConfig().getInt("warehouse.expand-step", 1000);
        double cost = plugin.getConfig().getDouble("warehouse.expand-cost", 5000);
        if (player == null || player.getBalance() < cost) {
            return false;
        }
        int capacity = getCapacity(owner) + step;
        boolean committed = database.executeTransaction("expand warehouse " + owner, connection -> {
            // Capacity only: the materials themselves live in wallet_warehouse.
            try (PreparedStatement upsert = connection.prepareStatement(
                    "REPLACE INTO idle_warehouse "
                            + "(owner_uuid, capacity, content_json) VALUES (?, ?, '')")) {
                upsert.setString(1, owner.toString());
                upsert.setInt(2, capacity);
                upsert.executeUpdate();
            }
            dev.branzx.idle.storage.CoinSql.debit(connection, player.getUuid(), Math.round(cost));
        });
        if (!committed) return false;
        capacities.put(owner, capacity);
        player.addBalance(-cost);
        return true;
    }

    private String serialize(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    private Map<String, Integer> deserialize(String value) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (value != null && !value.isBlank()) {
            for (String entry : value.split(";")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    map.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
                }
            }
        }
        return map;
    }
}
