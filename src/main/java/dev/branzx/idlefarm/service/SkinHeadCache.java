package dev.branzx.idlefarm.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Resolves player-name textures once and reuses the completed profile for
 * worker contracts and GUI icons. Unresolved heads deliberately remain
 * untextured so inventory serialization cannot start another profile lookup.
 */
public final class SkinHeadCache {

    private static final long RETRY_DELAY_MILLIS = Duration.ofMinutes(5).toMillis();

    private final IdleFarmPlugin plugin;
    private final Function<String, CompletableFuture<? extends PlayerProfile>> resolver;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public SkinHeadCache(IdleFarmPlugin plugin) {
        this(plugin, name -> Bukkit.createProfile(name).update(), System::currentTimeMillis);
    }

    SkinHeadCache(IdleFarmPlugin plugin,
                  Function<String, CompletableFuture<? extends PlayerProfile>> resolver,
                  LongSupplier clock) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.clock = clock;
    }

    public ItemStack createHead(String requestedName) {
        String name = normalizeName(requestedName);
        Entry entry = entries.computeIfAbsent(cacheKey(name), ignored -> new Entry(name));
        PlayerProfile profile = entry.profile;
        if (profile == null) {
            request(entry);
        }

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (profile != null && item.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(profile.clone());
            item.setItemMeta(skull);
        }
        return item;
    }

    void prefetch(String requestedName) {
        String name = normalizeName(requestedName);
        request(entries.computeIfAbsent(cacheKey(name), ignored -> new Entry(name)));
    }

    private void request(Entry entry) {
        synchronized (entry) {
            if (entry.profile != null || entry.loading || clock.getAsLong() < entry.retryAfter) {
                return;
            }
            entry.loading = true;
        }

        CompletableFuture<? extends PlayerProfile> lookup;
        try {
            lookup = resolver.apply(entry.name);
        } catch (RuntimeException error) {
            failed(entry, error);
            return;
        }
        lookup.whenComplete((profile, error) -> {
            if (error != null || profile == null || !profile.isComplete()) {
                failed(entry, error);
                return;
            }
            synchronized (entry) {
                entry.profile = profile.clone();
                entry.loading = false;
                entry.retryAfter = 0L;
            }
        });
    }

    private void failed(Entry entry, Throwable error) {
        synchronized (entry) {
            entry.loading = false;
            entry.retryAfter = clock.getAsLong() + RETRY_DELAY_MILLIS;
        }
        String detail = error == null || error.getMessage() == null
                ? "incomplete profile"
                : error.getMessage();
        plugin.getLogger().warning("Skin lookup for '" + entry.name
                + "' failed; retrying in 5 minutes (" + detail + ").");
    }

    private static String normalizeName(String name) {
        return name == null || name.isBlank() ? "Steve" : name.trim();
    }

    private static String cacheKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        private final String name;
        private volatile PlayerProfile profile;
        private boolean loading;
        private long retryAfter;

        private Entry(String name) {
            this.name = name;
        }
    }
}
