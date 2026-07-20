package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Drop tables live in their own {@code drops.yml} (seeded from config.yml
 * defaults on first run) so the in-game Pool Editor can write them back
 * without clobbering the commented main config. Supports optional
 * bracket-N subsections per node type, same as before.
 */
public final class DropTableService {

    private static final Set<String> FORBIDDEN_PASSIVE_DROPS =
            Set.of("ELYTRA", "NETHER_STAR", "DRAGON_EGG", "HEAVY_CORE",
                    "ENCHANTED_GOLDEN_APPLE", "WITHER_SKELETON_SKULL");

    private static final class MutableResource {
        private final Set<String> sources = new java.util.LinkedHashSet<>();
        private int unlockLevel = Integer.MAX_VALUE;
    }

    private final IdleFarmPlugin plugin;
    private final File file;
    private final File draftFile;
    private YamlConfiguration yaml;
    private YamlConfiguration draft;

    public record WorkflowStatus(boolean draftDirty, int validationErrors,
                                 String publishedRevision, int rollbackRevisions) {
        public boolean publishable() {
            return validationErrors == 0;
        }
    }

    public record PublishResult(boolean success, String revision, List<String> errors) {
    }

    public record RollbackResult(boolean success, String revision, String error) {
    }

    /** Auditable economy contract for every resource that content can emit. */
    public record ResourcePolicy(String resource, Set<String> sources, String sink,
                                 int unlockLevel, String cap) {
        public boolean complete() {
            return !sources.isEmpty() && sink != null && !sink.isBlank()
                    && unlockLevel >= 1 && cap != null && !cap.isBlank();
        }
    }

    public DropTableService(IdleFarmPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drops.yml");
        this.draftFile = new File(plugin.getDataFolder(), "drops-draft.yml");
    }

    public void load() {
        if (!file.exists()) {
            seedFromConfig();
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
        sanitizeForbiddenPassiveDrops();
        List<String> errors = validateConfiguration(yaml);
        if (!errors.isEmpty()) {
            errors.forEach(error -> plugin.getLogger().severe("Drop-table validation: " + error));
            throw new IllegalStateException("Refusing to publish invalid drop tables ("
                    + errors.size() + " validation errors)");
        }
        if (!draftFile.exists()) {
            copy(file, draftFile, "create drop-table draft");
        }
        this.draft = YamlConfiguration.loadConfiguration(draftFile);
    }

    private void sanitizeForbiddenPassiveDrops() {
        boolean changed = false;
        for (String path : new java.util.ArrayList<>(yaml.getKeys(true))) {
            if (yaml.isConfigurationSection(path)) continue;
            String leaf = path.substring(path.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
            if ("WOOL".equals(leaf)) {
                String parent = path.substring(0, path.lastIndexOf('.') + 1);
                if (!yaml.isSet(parent + "white_wool")) {
                    yaml.set(parent + "white_wool", yaml.get(path));
                }
                yaml.set(path, null);
                changed = true;
                continue;
            }
            if ("SCUTE".equals(leaf)) {
                String parent = path.substring(0, path.lastIndexOf('.') + 1);
                if (!yaml.isSet(parent + "turtle_scute")) {
                    yaml.set(parent + "turtle_scute", yaml.get(path));
                }
                yaml.set(path, null);
                changed = true;
                continue;
            }
            if (FORBIDDEN_PASSIVE_DROPS.contains(leaf)) {
                yaml.set(path, null);
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Content publish gate: every launch family must have ten non-empty
     * effective brackets and every output must be a real item material.
     */
    public List<String> validate() {
        return validateDraft();
    }

    public List<String> validateDraft() {
        return validateConfiguration(draft);
    }

    public List<String> validatePublished() {
        return validateConfiguration(yaml);
    }

    private List<String> validateConfiguration(YamlConfiguration candidate) {
        List<String> errors = new java.util.ArrayList<>();
        for (NodeType type : NodeType.values()) {
            if (!type.isProduction()) continue;
            for (int bracket = 1; bracket <= 10; bracket++) {
                Map<String, Double> effective = table(candidate, type, bracket);
                if (effective.isEmpty()) {
                    errors.add(type + " bracket-" + bracket + " is empty");
                    continue;
                }
                for (Map.Entry<String, Double> entry : effective.entrySet()) {
                    Material material = Material.matchMaterial(entry.getKey());
                    if (!isItemMaterial(material)) {
                        errors.add(type + " bracket-" + bracket + " has invalid item " + entry.getKey());
                    }
                    if (FORBIDDEN_PASSIVE_DROPS.contains(entry.getKey().toUpperCase(Locale.ROOT))) {
                        errors.add(type + " bracket-" + bracket + " has forbidden passive drop "
                                + entry.getKey());
                    }
                    if (!Double.isFinite(entry.getValue()) || entry.getValue() <= 0) {
                        errors.add(type + " bracket-" + bracket + " has invalid weight for " + entry.getKey());
                    }
                }
            }
        }
        resourcePolicies(candidate).values().stream()
                .filter(policy -> !policy.complete())
                .forEach(policy -> errors.add(policy.resource()
                        + " is missing source/sink/unlock/cap economy metadata"));
        return List.copyOf(errors);
    }

    public Map<String, ResourcePolicy> resourcePolicies(boolean useDraft) {
        return resourcePolicies(useDraft ? draft : yaml);
    }

    private Map<String, ResourcePolicy> resourcePolicies(YamlConfiguration candidate) {
        Map<String, MutableResource> discovered = new LinkedHashMap<>();
        int bracketSize = Math.max(1, plugin.getConfig().getInt("exploration.bracket-size", 10));
        for (NodeType type : NodeType.values()) {
            if (!type.isProduction()) continue;
            String typeKey = type.name().toLowerCase(Locale.ROOT);
            ConfigurationSection section = candidate.getConfigurationSection(typeKey);
            if (section == null) continue;
            boolean bracketed = section.getConfigurationSection("bracket-1") != null;
            if (bracketed) {
                for (int bracket = 1; bracket <= 10; bracket++) {
                    ConfigurationSection table = section.getConfigurationSection("bracket-" + bracket);
                    if (table == null) continue;
                    int unlock = (bracket - 1) * bracketSize + 1;
                    for (String resource : table.getKeys(false)) {
                        if (table.isConfigurationSection(resource) || table.getDouble(resource) <= 0) continue;
                        addResource(discovered, resource, type + "/bracket-" + bracket, unlock);
                    }
                }
            } else {
                for (String resource : section.getKeys(false)) {
                    if (!section.isConfigurationSection(resource) && section.getDouble(resource) > 0) {
                        addResource(discovered, resource, type + "/all-brackets", 1);
                    }
                }
            }
        }
        ConfigurationSection events = plugin.getConfig().getConfigurationSection("exploration.events");
        if (events != null) {
            for (String eventId : events.getKeys(false)) {
                ConfigurationSection event = events.getConfigurationSection(eventId);
                if (event == null) continue;
                int unlock = (Math.max(1, event.getInt("min-bracket", 1)) - 1) * bracketSize + 1;
                ConfigurationSection loot = event.getConfigurationSection("loot");
                if (loot == null) continue;
                for (String resource : loot.getKeys(false)) {
                    if (loot.getDouble(resource) > 0) {
                        addResource(discovered, resource, "EVENT/" + eventId, unlock);
                    }
                }
            }
        }
        Map<String, Integer> frontier = Map.ofEntries(
                Map.entry("AETHER_ORE", 101), Map.entry("MANA_HERB", 101),
                Map.entry("SPIRITWOOD_RESIN", 101), Map.entry("MYSTIC_HIDE", 101),
                Map.entry("SOUL_ASH", 101),
                Map.entry("MYTHRIL_FRAGMENT", 126), Map.entry("LIFE_SEED", 126),
                Map.entry("ANCIENT_BARK", 126), Map.entry("BEAST_CORE", 126),
                Map.entry("CORRUPTED_ESSENCE", 126),
                Map.entry("RESONANT_CRYSTAL", 176), Map.entry("ASTRAL_POLLEN", 176),
                Map.entry("LIVING_FIBER", 176), Map.entry("PRIMAL_BLOOD", 176),
                Map.entry("VOID_FANG", 176));
        frontier.forEach((resource, unlock) ->
                addResource(discovered, resource, "FRONTIER_PRODUCTION", unlock));
        Map<String, ResourcePolicy> result = new LinkedHashMap<>();
        discovered.forEach((resource, data) -> result.put(resource,
                new ResourcePolicy(resource, Set.copyOf(data.sources), resourceSink(resource),
                        data.unlockLevel, resourceCap(resource))));
        return Map.copyOf(result);
    }

    private void addResource(Map<String, MutableResource> resources, String resource,
                             String source, int unlock) {
        MutableResource policy = resources.computeIfAbsent(resource.toUpperCase(Locale.ROOT),
                ignored -> new MutableResource());
        policy.sources.add(source);
        policy.unlockLevel = Math.min(policy.unlockLevel, unlock);
    }

    private String resourceSink(String resource) {
        Material material = Material.matchMaterial(resource);
        if (material == null) return "FRONTIER_CRAFTING/REPAIR/PROJECT";
        if (isEdible(material)) return "PLAYER_CONSUMPTION";
        if (isBlock(material)) return "BUILDING_AND_CRAFTING";
        return "CRAFTING_TRADING_OR_PROJECT";
    }

    private String resourceCap(String resource) {
        String normalized = resource.toUpperCase(Locale.ROOT);
        if (dev.branzx.idlefarm.service.design.RareResources.DAILY.contains(normalized)) {
            return "ACCOUNT_DAILY_RARE_CAP";
        }
        if (dev.branzx.idlefarm.service.design.RareResources.WEEKLY.contains(normalized)) {
            return "ACCOUNT_WEEKLY_RARE_CAP";
        }
        if (Material.matchMaterial(normalized) == null) {
            return "ACCOUNT_MONTHLY_"
                    + plugin.getConfig().getInt("frontier.material-monthly-cap", 10000);
        }
        return "NODE_BUFFER_"
                + plugin.getConfig().getInt("production.buffer-capacity-per-tier", 256)
                + "_PER_TIER";
    }

    public Map<String, Double> additions(NodeType type, int bracket) {
        if (bracket <= 1) return table(type, 1);
        Map<String, Double> previous = table(type, bracket - 1);
        Map<String, Double> current = table(type, bracket);
        Map<String, Double> additions = new LinkedHashMap<>();
        current.forEach((material, weight) -> {
            if (!previous.containsKey(material)) additions.put(material, weight);
        });
        return additions;
    }

    private void seedFromConfig() {
        YamlConfiguration seed = new YamlConfiguration();
        ConfigurationSection defaults = plugin.getConfig().getConfigurationSection("production.drop-tables");
        if (defaults != null) {
            // Deep copy (handles flat tables and nested bracket-N sections).
            for (Map.Entry<String, Object> entry : defaults.getValues(true).entrySet()) {
                if (!(entry.getValue() instanceof ConfigurationSection)) {
                    seed.set(entry.getKey(), entry.getValue());
                }
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            seed.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to seed drops.yml: " + e.getMessage());
        }
    }

    /** Bracket sub-tables a type defines (empty if it uses a flat table). */
    public List<String> brackets(NodeType type) {
        ConfigurationSection section = yaml.getConfigurationSection(type.name().toLowerCase(Locale.ROOT));
        List<String> result = new java.util.ArrayList<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key.startsWith("bracket-")) {
                    result.add(key);
                }
            }
        }
        result.sort(java.util.Comparator.comparingInt(this::bracketNumber));
        return result;
    }

    private int bracketNumber(String key) {
        try {
            return Integer.parseInt(key.substring("bracket-".length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Effective weighted table for a node type at a bracket. Bracket sections
     * are additive: later brackets override weights they explicitly tune and
     * retain every earlier resource. A flat section applies to every bracket.
     */
    public Map<String, Double> table(NodeType type, int bracket) {
        return table(yaml, type, bracket);
    }

    private Map<String, Double> table(YamlConfiguration source, NodeType type, int bracket) {
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        ConfigurationSection section = source.getConfigurationSection(typeKey);
        if (section == null) {
            return Map.of();
        }
        if (section.getConfigurationSection("bracket-1") != null) {
            Map<String, Double> cumulative = new LinkedHashMap<>();
            for (int i = 1; i <= bracket; i++) {
                ConfigurationSection candidate = section.getConfigurationSection("bracket-" + i);
                if (candidate != null) {
                    for (String key : candidate.getKeys(false)) {
                        if (!candidate.isConfigurationSection(key)) {
                            double weight = candidate.getDouble(key);
                            if (weight > 0) {
                                cumulative.put(key, weight);
                            } else {
                                cumulative.remove(key);
                            }
                        }
                    }
                }
            }
            return Map.copyOf(cumulative);
        }
        Map<String, Double> table = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            table.put(key, section.getDouble(key));
        }
        return Map.copyOf(table);
    }

    /**
     * Effective table including the staged post-100 material share. Vanilla
     * output is always retained and disabling the flag stops all future
     * Frontier drops without touching owned items.
     */
    public Map<String, Double> table(NodeType type, int bracket, int level) {
        return table(type, bracket, level, null);
    }

    public Map<String, Double> table(NodeType type, int bracket, int level, UUID owner) {
        Map<String, Double> vanilla = table(type, bracket);
        if (level <= 100 || !frontierReady(owner)) return vanilla;
        double frontierShare = level <= 110 ? 0.10 : level <= 125 ? 0.20
                : level <= 150 ? 0.30 : level <= 175 ? 0.40 : 0.50;
        Map<String, Double> result = new LinkedHashMap<>();
        double vanillaTotal = vanilla.values().stream().mapToDouble(Double::doubleValue).sum();
        vanilla.forEach((key, weight) ->
                result.put(key, vanillaTotal <= 0 ? 0 : weight / vanillaTotal * (1.0 - frontierShare) * 100));
        List<String> materials = frontierMaterials(type, level);
        double each = frontierShare * 100 / materials.size();
        materials.forEach(material -> result.put(material, each));
        return result;
    }

    public boolean frontierReady() {
        return frontierReady(null);
    }

    /**
     * Account-aware staged rollout. A null owner only reports global sink
     * readiness and is retained for validation/admin status.
     */
    public boolean frontierReady(UUID owner) {
        if (!(plugin.getConfig().getBoolean("frontier.enabled", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.profession", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.equipment", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.repair", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.project", false))) {
            return false;
        }
        if (owner == null) return true;
        String root = "live-ops.features.frontier-drops";
        if (!plugin.getConfig().getBoolean(root + ".enabled", false)) return false;
        List<String> allowlist = plugin.getConfig().getStringList(root + ".allowlist");
        if (!allowlist.isEmpty() && allowlist.stream().noneMatch(value ->
                value.equalsIgnoreCase(owner.toString()))) return false;
        double percent = Math.max(0, Math.min(100,
                plugin.getConfig().getDouble(root + ".rollout-percent", 0)));
        if (percent >= 100) return true;
        long mixed = owner.getMostSignificantBits()
                ^ Long.rotateLeft(owner.getLeastSignificantBits(), 17)
                ^ "feature:frontier-drops".hashCode() * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return Math.floorMod(mixed, 10_000) < Math.round(percent * 100);
    }

    private List<String> frontierMaterials(NodeType type, int level) {
        List<String> all = switch (type) {
            case MINING -> List.of("AETHER_ORE", "MYTHRIL_FRAGMENT", "RESONANT_CRYSTAL");
            case FARMING -> List.of("MANA_HERB", "LIFE_SEED", "ASTRAL_POLLEN");
            case WOODCUTTING -> List.of("SPIRITWOOD_RESIN", "ANCIENT_BARK", "LIVING_FIBER");
            case LIVESTOCK -> List.of("MYSTIC_HIDE", "BEAST_CORE", "PRIMAL_BLOOD");
            case HUNTER -> List.of("SOUL_ASH", "CORRUPTED_ESSENCE", "VOID_FANG");
            default -> List.of("FRONTIER_FRAGMENT");
        };
        int count = level >= 176 ? 3 : level >= 126 ? 2 : 1;
        return all.subList(0, Math.min(count, all.size()));
    }

    public boolean isCustomMaterial(String id) {
        return id != null && Material.matchMaterial(id) == null;
    }

    public ItemStack customItem(String id, int amount) {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(pretty(id), NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text("MMORPG Frontier crafting material", NamedTextColor.GRAY),
                Component.text("Bound to the resource economy; no Credit value", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "frontier_material"),
                PersistentDataType.STRING, id.toUpperCase(Locale.ROOT));
        item.setItemMeta(meta);
        return item;
    }

    private String pretty(String id) {
        String value = id.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Raw editable table at a path ("mining" or "mining.bracket-2"). */
    public Map<String, Double> editable(String path) {
        Map<String, Double> table = new LinkedHashMap<>();
        ConfigurationSection section = draft.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!section.isConfigurationSection(key)) {
                    table.put(key, section.getDouble(key));
                }
            }
        }
        return table;
    }

    public boolean setWeight(String path, String material, double weight) {
        if (!validEditablePath(path)) {
            plugin.getLogger().warning("Rejected invalid drop-table path: " + path);
            return false;
        }
        Material parsed = Material.matchMaterial(material);
        if (!isItemMaterial(parsed) || !Double.isFinite(weight)) {
            plugin.getLogger().warning("Rejected invalid drop-table edit: " + material + "=" + weight);
            return false;
        }
        String key = path + "." + material.toLowerCase(Locale.ROOT);
        draft.set(key, weight <= 0 ? null : weight);
        saveDraft();
        return true;
    }

    private boolean validEditablePath(String path) {
        if (path == null) return false;
        String normalized = path.toLowerCase(Locale.ROOT);
        for (NodeType type : NodeType.values()) {
            if (!type.isProduction()) continue;
            String prefix = type.name().toLowerCase(Locale.ROOT);
            if (normalized.equals(prefix)) return true;
            if (normalized.matches(java.util.regex.Pattern.quote(prefix)
                    + "\\.bracket-(?:[1-9]|10)")) return true;
        }
        return false;
    }

    private String backupRevision() {
        if (!file.exists()) return null;
        File directory = new File(plugin.getDataFolder(), "content-revisions");
        directory.mkdirs();
        String id = Instant.now().toString().replace(':', '-') + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        File revision = new File(directory, "drops-" + id + ".yml");
        try {
            Files.copy(file.toPath(), revision.toPath());
            return id;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create drop-table revision: " + e.getMessage());
            return null;
        }
    }

    public PublishResult publish() {
        List<String> errors = validateDraft();
        if (!errors.isEmpty()) {
            return new PublishResult(false, null, errors);
        }
        String revision = backupRevision();
        if (file.exists() && revision == null) {
            return new PublishResult(false, null, List.of("Could not create rollback revision"));
        }
        try {
            atomicReplace(draftFile, file);
            this.yaml = YamlConfiguration.loadConfiguration(file);
            return new PublishResult(true, revision, List.of());
        } catch (IOException e) {
            plugin.getLogger().severe("Drop-table publish failed: " + e.getMessage());
            return new PublishResult(false, null, List.of(e.getMessage()));
        }
    }

    public boolean rollbackLatest() {
        return rollback().success();
    }

    public RollbackResult rollback() {
        File directory = new File(plugin.getDataFolder(), "content-revisions");
        File[] revisions = directory.listFiles((dir, name) ->
                name.startsWith("drops-") && name.endsWith(".yml"));
        if (revisions == null || revisions.length == 0) {
            return new RollbackResult(false, null, "No published revision is available");
        }
        java.util.Arrays.sort(revisions, Comparator.comparingLong(File::lastModified).reversed());
        YamlConfiguration candidate = YamlConfiguration.loadConfiguration(revisions[0]);
        List<String> errors = validateConfiguration(candidate);
        if (!errors.isEmpty()) {
            return new RollbackResult(false, revisionId(revisions[0]),
                    "Latest revision is invalid: " + errors.getFirst());
        }
        try {
            atomicReplace(revisions[0], file);
            atomicReplace(revisions[0], draftFile);
            this.yaml = YamlConfiguration.loadConfiguration(file);
            this.draft = YamlConfiguration.loadConfiguration(draftFile);
            return new RollbackResult(true, revisionId(revisions[0]), null);
        } catch (IOException e) {
            plugin.getLogger().severe("Drop-table rollback failed: " + e.getMessage());
            return new RollbackResult(false, revisionId(revisions[0]), e.getMessage());
        }
    }

    public WorkflowStatus status() {
        return new WorkflowStatus(!configurationValues(yaml).equals(configurationValues(draft)),
                validateDraft().size(), publishedFingerprint(), revisionCount());
    }

    public void resetDraft() {
        copy(file, draftFile, "reset drop-table draft");
        this.draft = YamlConfiguration.loadConfiguration(draftFile);
    }

    private Map<String, Object> configurationValues(YamlConfiguration configuration) {
        if (configuration == null) return Map.of();
        Map<String, Object> values = new LinkedHashMap<>();
        configuration.getValues(true).forEach((path, value) -> {
            if (!(value instanceof ConfigurationSection)) values.put(path, value);
        });
        return values;
    }

    private String publishedFingerprint() {
        if (!file.exists()) return "none";
        return Long.toHexString(file.lastModified()) + "-" + Long.toHexString(file.length());
    }

    private int revisionCount() {
        File directory = new File(plugin.getDataFolder(), "content-revisions");
        File[] revisions = directory.listFiles((dir, name) ->
                name.startsWith("drops-") && name.endsWith(".yml"));
        return revisions == null ? 0 : revisions.length;
    }

    private String revisionId(File revision) {
        String name = revision.getName();
        return name.substring("drops-".length(), name.length() - ".yml".length());
    }

    private void copy(File source, File target, String operation) {
        try {
            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to " + operation, e);
        }
    }

    private void atomicReplace(File source, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save drops.yml: " + e.getMessage());
        }
    }

    private void saveDraft() {
        try {
            draft.save(draftFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save drops-draft.yml: " + e.getMessage());
        }
    }

    private boolean isItemMaterial(Material material) {
        if (material == null || material.name().endsWith("AIR")) return false;
        try {
            return material.isItem();
        } catch (IllegalStateException | ExceptionInInitializerError | NoClassDefFoundError noRegistryOutsideServer) {
            // Paper 1.21+ resolves item types through the live registry. Unit
            // tests have no server registry, but enum matches are still safe.
            return !Set.of("WATER", "LAVA", "FIRE", "SOUL_FIRE", "NETHER_PORTAL",
                    "END_PORTAL", "END_GATEWAY").contains(material.name());
        }
    }

    private boolean isEdible(Material material) {
        try {
            return material.isEdible();
        } catch (IllegalStateException | ExceptionInInitializerError | NoClassDefFoundError noRegistryOutsideServer) {
            return false;
        }
    }

    private boolean isBlock(Material material) {
        try {
            return material.isBlock();
        } catch (IllegalStateException | ExceptionInInitializerError | NoClassDefFoundError noRegistryOutsideServer) {
            return false;
        }
    }
}
