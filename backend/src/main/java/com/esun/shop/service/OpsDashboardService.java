package com.esun.shop.service;

import com.esun.shop.dto.OpsDashboardView;
import com.esun.shop.repository.OpsDashboardRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Read model for the admin operations dashboard. The database figures are cached for a short TTL (one consistent
 * snapshot, single-flight, failures never cached) so an open dashboard cannot hammer the unindexed "today" scans; the
 * Redis mode is process-local memory and is read live on every call. The figures come from separate statements, not one
 * transaction, so they are individually correct but not a mutually consistent snapshot.
 *
 * The dashboard's day is Asia/Taipei; timestamps are stored as {@code ops.dashboard.storage-zone} (UTC) wall-clock,
 * which is why {@link DashboardWindow} converts the bounds before they reach SQL.
 */
@Service
public class OpsDashboardService {
    static final int LOW_STOCK_LIMIT = 20;
    private static final String REDIS_SCOPE = "THIS_INSTANCE";

    private record Snapshot(OffsetDateTime asOf, DashboardWindow window, OpsDashboardRepository.OrderTotals orders,
                            long pendingPayment, long refundRequired, long paymentFailures, long lowStockCount,
                            List<OpsDashboardRepository.LowStockProduct> lowStock) { }

    private record Cached(Snapshot snapshot, long expiresAtNanos) { }

    private final OpsDashboardRepository repository;
    private final StockCacheService stockCache;
    private final int lowStockThreshold;
    private final ZoneId storageZone;
    private final long ttlNanos;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private volatile Cached cached;

    @Autowired
    public OpsDashboardService(OpsDashboardRepository repository, StockCacheService stockCache,
                               @Value("${ops.dashboard.low-stock-threshold:5}") int lowStockThreshold,
                               @Value("${ops.dashboard.cache-ttl-seconds:15}") long ttlSeconds,
                               @Value("${ops.dashboard.storage-zone:UTC}") String storageZone) {
        this(repository, stockCache, lowStockThreshold, Duration.ofSeconds(Math.max(0, ttlSeconds)),
                ZoneId.of(storageZone), Clock.systemUTC(), System::nanoTime);
    }

    OpsDashboardService(OpsDashboardRepository repository, StockCacheService stockCache, int lowStockThreshold,
                        Duration ttl, ZoneId storageZone, Clock clock, LongSupplier nanoClock) {
        this.repository = repository;
        this.stockCache = stockCache;
        this.lowStockThreshold = lowStockThreshold;
        this.ttlNanos = ttl.toNanos();
        this.storageZone = storageZone;
        this.clock = clock;
        this.nanoClock = nanoClock;
    }

    public OpsDashboardView get() {
        Snapshot s = snapshot();
        List<OpsDashboardView.Item> items = s.lowStock().stream()
                .map(p -> new OpsDashboardView.Item(p.productId(), p.productName(), p.quantity())).toList();
        return new OpsDashboardView(s.asOf(), DashboardWindow.BUSINESS_ZONE.getId(),
                new OpsDashboardView.Today(s.window().from(), s.window().to(), s.orders().orderCount(),
                        s.orders().revenue(), s.paymentFailures()),
                s.pendingPayment(), s.refundRequired(),
                new OpsDashboardView.LowStock(lowStockThreshold, s.lowStockCount(), items),
                new OpsDashboardView.Redis(stockCache.dependencySnapshot().mode().name(), REDIS_SCOPE));
    }

    private Snapshot snapshot() {
        if (ttlNanos <= 0) return load();
        Cached c = cached;
        if (c != null && fresh(c)) return c.snapshot();
        synchronized (this) {
            c = cached;
            if (c != null && fresh(c)) return c.snapshot();
            Snapshot loaded = load();
            cached = new Cached(loaded, nanoClock.getAsLong() + ttlNanos);
            return loaded;
        }
    }

    private boolean fresh(Cached c) {
        return nanoClock.getAsLong() - c.expiresAtNanos() < 0;
    }

    private Snapshot load() {
        var now = clock.instant();
        DashboardWindow window = DashboardWindow.today(now, storageZone);
        return new Snapshot(now.atZone(DashboardWindow.BUSINESS_ZONE).toOffsetDateTime(), window,
                repository.ordersCreated(window.storedFrom(), window.storedTo()),
                repository.pendingPayment(), repository.refundRequired(),
                repository.paymentFailures(window.storedFrom(), window.storedTo()),
                repository.lowStockCount(lowStockThreshold),
                repository.lowStock(lowStockThreshold, LOW_STOCK_LIMIT));
    }
}
