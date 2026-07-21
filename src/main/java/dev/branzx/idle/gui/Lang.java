package dev.branzx.idle.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Player-facing text lookup. Strings live in {@code lang/<code>.yml} so servers can
 * translate without touching code; the bundled English file is always merged in as
 * defaults so a new key never renders blank after a plugin update.
 *
 * <p>Keys are dotted and grouped by screen, e.g. {@code menu.home.warehouse.name}.
 * Placeholders use {@code {name}} and are filled positionally by {@link #get}.
 */
public final class Lang {

    /** Language shipped with the plugin; always loaded as the default layer. */
    public static final String FALLBACK = "en";

    private static Lang instance;

    private final Plugin plugin;
    private final YamlConfiguration messages;
    private final Set<String> reportedMissing = new HashSet<>();

    private Lang(Plugin plugin, YamlConfiguration messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Loads the language selected by {@code language} in config.yml. Call once on enable. */
    public static void init(Plugin plugin) {
        String code = plugin.getConfig().getString("language", FALLBACK)
                .toLowerCase(Locale.ROOT);
        instance = new Lang(plugin, load(plugin, code));
    }

    private static YamlConfiguration load(Plugin plugin, String code) {
        String path = "lang/" + code + ".yml";
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists() && plugin.getResource(path) != null) {
            plugin.saveResource(path, false);
        }
        YamlConfiguration yaml = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        if (!file.exists()) {
            plugin.getLogger().warning("Language '" + code + "' has no lang/" + code
                    + ".yml; falling back to " + FALLBACK + ".");
        }
        try (InputStream bundled = plugin.getResource("lang/" + FALLBACK + ".yml")) {
            if (bundled != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
                // Write new keys back only into the English file. saveResource
                // never overwrites, so without this an admin editing en.yml
                // would never see strings added by an update. Existing values
                // win, so their edits survive. Translations are left untouched:
                // merging would bury English lines inside a translated file,
                // and the defaults layer above already covers what is missing.
                if (FALLBACK.equals(code)) {
                    yaml.options().copyDefaults(true);
                    yaml.save(file);
                } else {
                    yaml.options().copyDefaults(false);
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not load bundled language defaults: "
                    + exception.getMessage());
        }
        return yaml;
    }

    /**
     * Resolves a key, replacing {@code {placeholder}} tokens from alternating
     * name/value pairs. An unknown key renders as the key itself and is logged once.
     */
    public static String get(String key, Object... placeholders) {
        if (instance == null) {
            return key;
        }
        String value = instance.messages.getString(key);
        if (value == null) {
            if (instance.reportedMissing.add(key)) {
                instance.plugin.getLogger().warning("Missing language key: " + key);
            }
            return key;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}",
                    String.valueOf(placeholders[i + 1]));
        }
        return value;
    }

    /** Convenience for lore lines: a non-italic component of the resolved key. */
    public static Component line(String key, NamedTextColor color, Object... placeholders) {
        return Ui.line(get(key, placeholders), color);
    }

    /** Convenience for the yellow hint line; the "Click:" wrapper is itself translatable. */
    public static Component click(String key, Object... placeholders) {
        return Ui.line(get("ui.click", "action", get(key, placeholders)),
                NamedTextColor.YELLOW);
    }
}
