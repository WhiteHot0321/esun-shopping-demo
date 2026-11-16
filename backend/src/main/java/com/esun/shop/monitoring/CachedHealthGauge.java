package com.esun.shop.monitoring;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Turns a {@link HealthIndicator} into a 1/0 gauge value for Prometheus, re-probing at most once per {@code ttl}.
 *
 * <p>The gauge is read on every scrape. Probing a remote dependency on each scrape would tie scrape latency and the
 * dependency's load to the scrape interval, so the last result is cached. The indicator itself carries a short
 * connect/read timeout, which bounds the cost of a probe that does run on the scrape thread.
 *
 * <p>Anything other than {@code UP}, including an exception thrown by the indicator, reports 0: the metric answers
 * "is the dependency usable right now", not "did the probe code work".
 */
final class CachedHealthGauge {

    private final HealthIndicator indicator;
    private final long ttlNanos;
    private final LongSupplier nanoClock;

    private boolean probed;
    private long lastProbeNanos;
    private double lastValue;

    CachedHealthGauge(HealthIndicator indicator, Duration ttl, LongSupplier nanoClock) {
        this.indicator = indicator;
        this.ttlNanos = ttl.toNanos();
        this.nanoClock = nanoClock;
    }

    synchronized double value() {
        long now = nanoClock.getAsLong();
        if (!probed || now - lastProbeNanos >= ttlNanos) {
            lastValue = probe();
            lastProbeNanos = now;
            probed = true;
        }
        return lastValue;
    }

    private double probe() {
        try {
            Health health = indicator.health();
            return health != null && Status.UP.equals(health.getStatus()) ? 1 : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
