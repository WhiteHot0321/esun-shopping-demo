package com.esun.shop.service;

import com.esun.shop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
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
}
