package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Config-driven live-ops kill switches, deterministic percentage rollouts and
 * experiment variants. Assignments are stable for a player and control key.
 */
public final class FeatureControlService {

    public record Variant(String id, int weight) {
    }

    private final IdleFarmPlugin plugin;

    public FeatureControlService(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled(String key, UUID owner) {
        String root = "live-ops.features." + key;
        if (!plugin.getConfig().getBoolean(root + ".enabled", true)) return false;
        List<String> allowlist = plugin.getConfig().getStringList(root + ".allowlist");
        if (!allowlist.isEmpty()) {
            if (owner == null || allowlist.stream().noneMatch(value ->
                    value.equalsIgnoreCase(owner.toString()))) return false;
        }
        double percent = Math.max(0, Math.min(100,
                plugin.getConfig().getDouble(root + ".rollout-percent", 100)));
        return percent >= 100 || owner != null
                && bucket(owner, "feature:" + key, 10_000) < Math.round(percent * 100);
    }

    public String variant(String experiment, UUID owner, String fallback) {
        String root = "live-ops.experiments." + experiment;
        if (!plugin.getConfig().getBoolean(root + ".enabled", false) || owner == null) {
            return fallback;
        }
        double percent = Math.max(0, Math.min(100,
                plugin.getConfig().getDouble(root + ".rollout-percent", 100)));
        if (bucket(owner, "experiment:" + experiment, 10_000) >= Math.round(percent * 100)) {
            return fallback;
        }
        ConfigurationSection section =
                plugin.getConfig().getConfigurationSection(root + ".variants");
        if (section == null) return fallback;
        List<Variant> variants = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            int weight = section.getInt(id, 0);
            if (weight > 0) variants.add(new Variant(id.toLowerCase(Locale.ROOT), weight));
        }
        variants.sort(Comparator.comparing(Variant::id));
        int total = variants.stream().mapToInt(Variant::weight).sum();
        if (total <= 0) return fallback;
        int selected = bucket(owner, "variant:" + experiment, total);
        for (Variant variant : variants) {
            selected -= variant.weight();
            if (selected < 0) return variant.id();
        }
        return fallback;
    }

    private int bucket(UUID owner, String salt, int bound) {
        long mixed = owner.getMostSignificantBits() ^ Long.rotateLeft(owner.getLeastSignificantBits(), 17)
                ^ salt.hashCode() * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return (int) Math.floorMod(mixed, bound);
    }
}
