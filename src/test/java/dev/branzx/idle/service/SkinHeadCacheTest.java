package dev.branzx.idle.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.branzx.idle.IdlePlugin;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkinHeadCacheTest {

    @Test
    void concurrentRequestsForTheSameNameShareOneLookup() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        AtomicInteger lookups = new AtomicInteger();
        CompletableFuture<PlayerProfile> pending = new CompletableFuture<>();
        SkinHeadCache cache = new SkinHeadCache(plugin, name -> {
            lookups.incrementAndGet();
            return pending;
        }, () -> 1_000L);

        cache.prefetch("Ph1LzA");
        cache.prefetch("ph1lza");

        assertEquals(1, lookups.get());

        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.isComplete()).thenReturn(true);
        when(profile.clone()).thenReturn(profile);
        pending.complete(profile);
        cache.prefetch("PH1LZA");

        assertEquals(1, lookups.get());
    }

    @Test
    void failedLookupWaitsFiveMinutesBeforeRetrying() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        AtomicInteger lookups = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000L);
        SkinHeadCache cache = new SkinHeadCache(plugin, name -> {
            lookups.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("rate limited"));
        }, clock::get);

        cache.prefetch("Notch");
        cache.prefetch("Notch");
        assertEquals(1, lookups.get());

        clock.addAndGet(5 * 60 * 1_000L + 1L);
        cache.prefetch("Notch");
        assertEquals(2, lookups.get());
    }
}
