package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.esun.shop.dto.RecommendationItem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicRecommendationCacheTest {
    private final RecommendationService service = mock(RecommendationService.class);
    private final AtomicLong now = new AtomicLong(1_000);

    private PublicRecommendationCache cache(Duration ttl) {
        return new PublicRecommendationCache(service, ttl, now::get);
    }

    @Test
    void repeatsInsideTtlHitTheDatabaseOnce() {
        when(service.forProduct("A", 6)).thenReturn(List.of());
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        cache.forProduct("A", 6);
        cache.forProduct("A", 6);
        verify(service, times(1)).forProduct("A", 6);
    }

    @Test
    void entryExpiresAfterTtlAndKeysAreIndependent() {
        when(service.forProduct(anyString(), anyInt())).thenReturn(List.of());
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        cache.forProduct("A", 6);
        cache.forProduct("A", 7); // different limit -> different key
        now.addAndGet(Duration.ofSeconds(31).toNanos());
        cache.forProduct("A", 6);
        verify(service, times(2)).forProduct("A", 6);
        verify(service, times(1)).forProduct("A", 7);
    }

    @Test
    void failuresAreNeverCached() {
        when(service.forProduct("MISSING", 6)).thenThrow(new BusinessException("找不到商品", HttpStatus.NOT_FOUND));
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        assertThatThrownBy(() -> cache.forProduct("MISSING", 6)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cache.forProduct("MISSING", 6)).isInstanceOf(BusinessException.class);
        verify(service, times(2)).forProduct("MISSING", 6);
    }

    @Test
    void zeroTtlDisablesCaching() {
        when(service.forProduct("A", 6)).thenReturn(List.of());
        PublicRecommendationCache cache = cache(Duration.ZERO);
        cache.forProduct("A", 6);
        cache.forProduct("A", 6);
        verify(service, times(2)).forProduct("A", 6);
    }

    @Test
    void concurrentMissesLoadOnce() throws Exception {
        when(service.forProduct("A", 6)).thenAnswer(inv -> {
            Thread.sleep(150);
            return List.of();
        });
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return cache.forProduct("A", 6);
                }));
            }
            go.countDown();
            for (Future<?> f : futures) assertThat(f.get()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
        verify(service, times(1)).forProduct("A", 6);
    }

    @Test
    void invalidLimitBypassesTheCacheSoItCannotGrowTheMap() {
        when(service.forProduct("A", 0)).thenThrow(new BusinessException("bad", HttpStatus.BAD_REQUEST));
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        assertThatThrownBy(() -> cache.forProduct("A", 0)).isInstanceOf(BusinessException.class);
        verify(service, times(1)).forProduct("A", 0);
    }

    // ---- 081 review: gaps not covered by the original six cases ----

    @Test
    void mapIsCappedAndOverflowFallsThroughUncachedUntilExpiredEntriesCanBeEvicted() {
        when(service.forProduct(anyString(), anyInt())).thenReturn(List.of());
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        for (int i = 0; i < PublicRecommendationCache.MAX_ENTRIES; i++) cache.forProduct("P" + i, 6);

        // Map is full of fresh entries: a new key is served but never stored, so every call reaches the service.
        cache.forProduct("OVERFLOW", 6);
        cache.forProduct("OVERFLOW", 6);
        verify(service, times(2)).forProduct("OVERFLOW", 6);
        // Existing keys keep hitting.
        cache.forProduct("P0", 6);
        verify(service, times(1)).forProduct("P0", 6);

        // After expiry the next new key evicts the expired entries and is cached again.
        now.addAndGet(Duration.ofSeconds(31).toNanos());
        clearInvocations(service);
        cache.forProduct("OVERFLOW", 6);
        cache.forProduct("OVERFLOW", 6);
        verify(service, times(1)).forProduct("OVERFLOW", 6);
    }

    @Test
    void failedRefreshAfterExpiryIsNotServedStaleAndIsRetried() {
        List<RecommendationItem> first = List.of(new RecommendationItem(null, "POPULAR", 3));
        List<RecommendationItem> second = List.of(new RecommendationItem(null, "POPULAR", 4));
        when(service.forProduct("A", 6))
                .thenReturn(first)
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(second);
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        assertThat(cache.forProduct("A", 6)).isEqualTo(first);

        now.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThatThrownBy(() -> cache.forProduct("A", 6)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.forProduct("A", 6)).isEqualTo(second);
        assertThat(cache.forProduct("A", 6)).isEqualTo(second); // the successful retry is cached
        verify(service, times(3)).forProduct("A", 6);
    }

    @Test
    void negativeConfiguredTtlDisablesCachingLikeZero() {
        when(service.forProduct("A", 6)).thenReturn(List.of());
        PublicRecommendationCache cache = new PublicRecommendationCache(service, -5);
        cache.forProduct("A", 6);
        cache.forProduct("A", 6);
        verify(service, times(2)).forProduct("A", 6);
    }

    @Test
    void cachedListIsAnImmutableSnapshotSharedSafelyBetweenCallers() {
        List<RecommendationItem> fromService = new ArrayList<>(List.of(new RecommendationItem(null, "POPULAR", 3)));
        when(service.forProduct("A", 6)).thenReturn(fromService);
        PublicRecommendationCache cache = cache(Duration.ofSeconds(30));
        List<RecommendationItem> hit = cache.forProduct("A", 6);

        fromService.clear(); // a later change to the loader's list must not leak into the cache
        assertThat(cache.forProduct("A", 6)).hasSize(1);
        assertThatThrownBy(() -> hit.add(new RecommendationItem(null, "X", 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
