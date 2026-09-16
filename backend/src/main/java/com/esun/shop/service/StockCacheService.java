package com.esun.shop.service;

import com.esun.shop.dto.OrderItemRequest;
import com.esun.shop.model.Product;
import com.esun.shop.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class StockCacheService {
    public enum Reservation { RESERVED, INSUFFICIENT, BYPASSED }
    private static final Logger log = LoggerFactory.getLogger(StockCacheService.class);
    private static final String PREFIX = "stock:";
    private final StringRedisTemplate redis;
    private final ProductRepository productRepository;
    private final boolean enabled;
    private final DefaultRedisScript<List> decreaseScript;
    private final AtomicBoolean degraded = new AtomicBoolean();
    private final DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>(
            "for i,k in ipairs(KEYS) do if not redis.call('GET',k) then return 0 end end "
            + "for i,k in ipairs(KEYS) do redis.call('INCRBY',k,ARGV[i]) end return 1", Long.class);

    public StockCacheService(StringRedisTemplate redis, ProductRepository productRepository,
                             @Value("${stock.redis.enabled:false}") boolean enabled) {
        this.redis = redis;
        this.productRepository = productRepository;
        this.enabled = enabled;
        try (InputStream input = new ClassPathResource("redis/stock-decrease.lua").getInputStream()) {
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            this.decreaseScript = new DefaultRedisScript<>(script, List.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load Redis stock script", e);
        }
    }

    public boolean isEnabled() { return enabled; }

    @EventListener(ApplicationReadyEvent.class)
    public void preloadOnStartup() {
        if (!enabled) return;
        try {
            preload();
        } catch (RuntimeException ex) {
            log.warn("Redis preload unavailable; DB-only fallback remains active", ex);
        }
    }

    public void preload() {
        if (!enabled) return;
        // Never overwrite reservations held by another running instance.
        productRepository.findAllStock().forEach(p -> redis.opsForValue().setIfAbsent(key(p.getProductId()), String.valueOf(p.getQuantity())));
    }

    public Reservation tryDecrease(List<OrderItemRequest> items) {
        if (!enabled || degraded.get()) return Reservation.BYPASSED;
        List<String> keys = items.stream().map(i -> key(i.getProductId())).toList();
        List<String> quantities = items.stream().map(i -> String.valueOf(i.getQuantity())).toList();
        try {
            List result = redis.execute(decreaseScript, keys, quantities.toArray());
            if (result == null || result.isEmpty()) throw new IllegalStateException("No Redis reservation result");
            long code = Long.parseLong(String.valueOf(result.get(0)));
            if (code == 1) return Reservation.RESERVED;
            if (code == 0) return Reservation.INSUFFICIENT;
            // A missing cache key must not make an existing DB product unsellable.
            degraded.set(true);
            log.warn("Redis stock missing; latched DB-only mode until maintenance reconciliation");
            return Reservation.BYPASSED;
        } catch (RuntimeException ex) {
            degraded.set(true);
            log.warn("Redis stock unavailable; using DB-only mode", ex);
            return Reservation.BYPASSED;
        }
    }

    public void compensate(List<OrderItemRequest> items) {
        if (!enabled) return;
        try {
            Long restored = redis.execute(compensateScript,
                    items.stream().map(i -> key(i.getProductId())).toList(),
                    items.stream().map(i -> String.valueOf(i.getQuantity())).toArray());
            if (!Long.valueOf(1).equals(restored)) throw new IllegalStateException("Reservation keys missing during compensation");
        } catch (RuntimeException ex) {
            degraded.set(true);
            log.error("Redis stock compensation failed", ex);
        }
    }

    public Map<String, Long> audit() {
        if (!enabled) return Map.of();
        Map<String, Long> db = productRepository.findAllStock().stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p.getQuantity().longValue()));
        return db.entrySet().stream().filter(e -> !String.valueOf(e.getValue()).equals(redis.opsForValue().get(key(e.getKey()))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Scheduled(fixedDelayString = "${stock.redis.audit-interval-ms:60000}")
    public void auditScheduled() {
        if (!enabled) return;
        try {
            Map<String, Long> drift = audit();
            if (!drift.isEmpty() || degraded.get()) log.warn("Stock audit drift={} degraded={}; reconcile with writes paused", drift, degraded.get());
            else log.info("Stock audit: no drift");
        } catch (RuntimeException ex) {
            log.warn("Stock audit unavailable", ex);
        }
    }

    private String key(String productId) { return PREFIX + productId; }
}
