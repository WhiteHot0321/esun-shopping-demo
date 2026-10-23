package com.esun.shop.service;

import com.esun.shop.dto.OpsDashboardView;
import com.esun.shop.repository.OpsDashboardRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsDashboardServiceTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    // 2026-10-22 23:59:59 Taipei
    private static final Instant NOW = Instant.parse("2026-10-22T15:59:59Z");

    private final OpsDashboardRepository repository = mock(OpsDashboardRepository.class);
    private final StockCacheService stockCache = mock(StockCacheService.class);
    private final AtomicLong nanos = new AtomicLong(1_000);

    private OpsDashboardService service(Duration ttl) {
        when(stockCache.dependencySnapshot())
                .thenReturn(new StockCacheService.DependencySnapshot(StockCacheService.DependencyMode.DISABLED));
        when(repository.ordersCreated(any(), any()))
                .thenReturn(new OpsDashboardRepository.OrderTotals(3, new BigDecimal("30.00")));
        when(repository.lowStock(anyInt(), anyInt())).thenReturn(
                List.of(new OpsDashboardRepository.LowStockProduct("P1", "Widget", 0)));
        when(repository.lowStockCount(anyInt())).thenReturn(1L);
        return new OpsDashboardService(repository, stockCache, 5, ttl, UTC,
                Clock.fixed(NOW, ZoneOffset.UTC), nanos::get);
    }

    @Test
    void composesTheViewAndQueriesWithTheStoredZoneWindow() {
        when(repository.pendingPayment()).thenReturn(4L);
        when(repository.refundRequired()).thenReturn(2L);
        when(repository.paymentFailures(any(), any())).thenReturn(7L);
        OpsDashboardService service = service(Duration.ZERO);

        OpsDashboardView view = service.get();

        // The Taipei day 2026-10-22 as UTC wall-clock, not Taipei wall-clock.
        verify(repository).ordersCreated(LocalDateTime.of(2026, 10, 21, 16, 0), LocalDateTime.of(2026, 10, 22, 16, 0));
        verify(repository).paymentFailures(LocalDateTime.of(2026, 10, 21, 16, 0), LocalDateTime.of(2026, 10, 22, 16, 0));
        assertThat(view.timezone()).isEqualTo("Asia/Taipei");
        assertThat(view.asOf().toString()).isEqualTo("2026-10-22T23:59:59+08:00");
        assertThat(view.today().from().toString()).isEqualTo("2026-10-22T00:00+08:00");
        assertThat(view.today().to().toString()).isEqualTo("2026-10-23T00:00+08:00");
        assertThat(view.today().orderCount()).isEqualTo(3);
        assertThat(view.today().revenue()).isEqualByComparingTo("30.00");
        assertThat(view.today().paymentFailureCount()).isEqualTo(7);
        assertThat(view.pendingPaymentCount()).isEqualTo(4);
        assertThat(view.refundRequiredCount()).isEqualTo(2);
        assertThat(view.lowStock().threshold()).isEqualTo(5);
        assertThat(view.lowStock().count()).isEqualTo(1);
        assertThat(view.lowStock().items()).extracting(OpsDashboardView.Item::productId).containsExactly("P1");
        assertThat(view.redis().mode()).isEqualTo("DISABLED");
        assertThat(view.redis().scope()).isEqualTo("THIS_INSTANCE");
    }

    @Test
    void repeatsInsideTtlReadTheDatabaseOnce() {
        OpsDashboardService service = service(Duration.ofSeconds(15));

        service.get();
        service.get();

        verify(repository, times(1)).ordersCreated(any(), any());
    }

    @Test
    void entryIsReloadedOnceTheTtlHasPassed() {
        OpsDashboardService service = service(Duration.ofSeconds(15));

        service.get();
        nanos.addAndGet(Duration.ofSeconds(16).toNanos());
        service.get();

        verify(repository, times(2)).ordersCreated(any(), any());
    }

    @Test
    void zeroTtlDisablesCaching() {
        OpsDashboardService service = service(Duration.ZERO);

        service.get();
        service.get();

        verify(repository, times(2)).ordersCreated(any(), any());
    }

    @Test
    void aFailedLoadIsNotCached() {
        OpsDashboardService service = service(Duration.ofSeconds(15));
        when(repository.ordersCreated(any(), any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(new OpsDashboardRepository.OrderTotals(1, new BigDecimal("1.00")));

        assertThatThrownBy(service::get).isInstanceOf(IllegalStateException.class);
        assertThat(service.get().today().orderCount()).isEqualTo(1);
    }

    @Test
    void redisModeIsReadLiveEvenWhenTheDatabaseFiguresAreCached() {
        OpsDashboardService service = service(Duration.ofSeconds(15));
        assertThat(service.get().redis().mode()).isEqualTo("DISABLED");

        when(stockCache.dependencySnapshot()).thenReturn(
                new StockCacheService.DependencySnapshot(StockCacheService.DependencyMode.LATCHED_DB_ONLY));

        assertThat(service.get().redis().mode()).isEqualTo("LATCHED_DB_ONLY");
        verify(repository, times(1)).ordersCreated(any(), any());
    }

    @Test
    void aBurstAtColdStartProducesOneDatabaseRead() throws Exception {
        OpsDashboardService service = service(Duration.ofSeconds(15));
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<OpsDashboardView>> results = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return service.get();
                }));
            }
            start.countDown();
            for (Future<OpsDashboardView> f : results) assertThat(f.get().today().orderCount()).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }

        verify(repository, times(1)).ordersCreated(any(), any());
    }
}
