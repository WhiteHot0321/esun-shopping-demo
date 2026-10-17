package com.esun.shop.service;

import com.esun.shop.dto.RecommendationItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Short-TTL, in-process cache for the public "also bought" list. The underlying aggregate costs ~0.5 s of database CPU
 * per call and the endpoint is unauthenticated, so repeats inside the TTL are served from memory.
 *
 * Contract change versus the uncached path: a result may be up to {@code ttl} old (new orders, restocks and price
 * changes appear after the entry expires). Each entry is still one consistent snapshot. Only successful results are
 * cached, so unknown products (404) never occupy memory; one loader runs per key at a time (single-flight), so a
 * burst at expiry produces one database call, not one per request. The personalised list is never cached.
 */
@Component
public class PublicRecommendationCache {
    static final int MAX_ENTRIES = 2_000;

    private record Key(String productId, int limit) {}

    private static final class Entry {
        volatile List<RecommendationItem> value;
        volatile long expiresAtNanos;
    }

    private final RecommendationService service;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public PublicRecommendationCache(RecommendationService service,
                                     @Value("${recommendation.cache-ttl-seconds:30}") long ttlSeconds) {
        this(service, Duration.ofSeconds(Math.max(0, ttlSeconds)), System::nanoTime);
    }

    PublicRecommendationCache(RecommendationService service, Duration ttl, LongSupplier clock) {
        this.service = service;
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
    }

    public List<RecommendationItem> forProduct(String productId, int limit) {
        if (ttlNanos <= 0) return service.forProduct(productId, limit);
        // Validate before creating an entry so bad limits cannot grow the map.
        if (limit < 1 || limit > RecommendationService.MAX_LIMIT) return service.forProduct(productId, limit);

        Key key = new Key(productId, limit);
        Entry entry = entries.get(key);
        if (entry != null && fresh(entry)) return entry.value;

        if (entry == null) {
            if (entries.size() >= MAX_ENTRIES) evictExpired();
            if (entries.size() >= MAX_ENTRIES) return service.forProduct(productId, limit);
            entry = entries.computeIfAbsent(key, k -> new Entry());
        }
        synchronized (entry) {
            if (fresh(entry)) return entry.value;
            try {
                List<RecommendationItem> loaded = List.copyOf(service.forProduct(productId, limit));
                entry.value = loaded;
                entry.expiresAtNanos = clock.getAsLong() + ttlNanos;
                return loaded;
            } catch (RuntimeException ex) {
                if (entry.value == null) entries.remove(key, entry);
                throw ex;
            }
        }
    }

    public void clear() {
        entries.clear();
    }

    private boolean fresh(Entry entry) {
        return entry.value != null && clock.getAsLong() - entry.expiresAtNanos < 0;
    }

    private void evictExpired() {
        entries.entrySet().removeIf(e -> e.getValue().value != null && !fresh(e.getValue()));
    }
}
