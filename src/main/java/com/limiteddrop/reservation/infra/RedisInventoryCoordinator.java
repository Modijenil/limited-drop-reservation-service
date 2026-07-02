package com.limiteddrop.reservation.infra;

import java.time.Duration;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisInventoryCoordinator {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
        "local current = tonumber(redis.call('GET', KEYS[1]) or '-1') " +
            "if current < 0 then return -2 end " +
            "local qty = tonumber(ARGV[1]) " +
            "if current < qty then return -1 end " +
            "return redis.call('DECRBY', KEYS[1], qty)",
        Long.class
    );

    private final StringRedisTemplate redisTemplate;

    @Value("${app.redis.enabled:true}")
    private boolean redisEnabled;

    public boolean tryReserve(Long dropId, int quantity) {
        if (!redisEnabled) {
            return true;
        }
        try {
            String key = availabilityKey(dropId);
            Long result = redisTemplate.execute(RESERVE_SCRIPT, Collections.singletonList(key), String.valueOf(quantity));
            if (result == null || result == -2L) {
                // Redis gave no usable answer (unavailable or counter not initialized) -> defer to the DB,
                // which is the authoritative source of truth and still prevents overselling.
                return true;
            }
            // -1 means the counter exists but lacks units; any value >= 0 is the post-decrement remainder.
            return result != -1L;
        } catch (Exception ignored) {
            return true;
        }
    }

    public void release(Long dropId, int quantity) {
        if (!redisEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().increment(availabilityKey(dropId), quantity);
        } catch (Exception ignored) {
        }
    }

    public void cacheAvailability(Long dropId, int availableUnits) {
        if (!redisEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(availabilityKey(dropId), String.valueOf(availableUnits), Duration.ofMinutes(10));
        } catch (Exception ignored) {
        }
    }

    public void createHoldTtlKey(String holdId, Duration ttl) {
        if (!redisEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(holdKey(holdId), "1", ttl);
        } catch (Exception ignored) {
        }
    }

    private String availabilityKey(Long dropId) {
        return "drop:" + dropId + ":avail";
    }

    private String holdKey(String holdId) {
        return "hold:" + holdId;
    }
}
