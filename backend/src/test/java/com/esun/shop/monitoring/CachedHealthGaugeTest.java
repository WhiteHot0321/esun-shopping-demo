package com.esun.shop.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CachedHealthGaugeTest {

    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger probes = new AtomicInteger();
    private final AtomicReference<Health> next = new AtomicReference<>(Health.up().build());

    private final HealthIndicator indicator = () -> {
        probes.incrementAndGet();
        Health health = next.get();
        if (health == null) throw new IllegalStateException("probe blew up");
        return health;
    };

    private final CachedHealthGauge gauge = new CachedHealthGauge(indicator, Duration.ofSeconds(30), clock::get);

    @Test
    void upIsOne_downIsZero() {
        assertThat(gauge.value()).isEqualTo(1.0);

        next.set(Health.down().withDetail("mode", "UNAVAILABLE").build());
        clock.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void unknownOrFailingProbeCountsAsUnavailable() {
        next.set(Health.unknown().build());
        assertThat(gauge.value()).isEqualTo(0.0);

        next.set(null);
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void reusesTheLastResultWithinTheTtlAndReprobesAfterIt() {
        assertThat(gauge.value()).isEqualTo(1.0);
        next.set(Health.down().build());

        clock.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(gauge.value()).as("cached UP inside the TTL").isEqualTo(1.0);
        assertThat(probes).hasValue(1);

        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(gauge.value()).as("re-probed once the TTL elapsed").isEqualTo(0.0);
        assertThat(probes).hasValue(2);
    }
}
