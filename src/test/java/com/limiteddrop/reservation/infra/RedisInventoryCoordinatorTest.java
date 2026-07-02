package com.limiteddrop.reservation.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RedisInventoryCoordinatorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private RedisInventoryCoordinator coordinator;

    private void enableRedis(boolean enabled) {
        ReflectionTestUtils.setField(coordinator, "redisEnabled", enabled);
    }

    @Test
    void tryReserveSucceedsWhenCounterHasUnits() {
        enableRedis(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(4L);

        assertThat(coordinator.tryReserve(1L, 1)).isTrue();
    }

    @Test
    void tryReserveRejectsWhenCounterInsufficient() {
        enableRedis(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-1L);

        assertThat(coordinator.tryReserve(1L, 5)).isFalse();
    }

    @Test
    void tryReserveDefersToDbWhenCounterUninitialized() {
        enableRedis(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-2L);

        assertThat(coordinator.tryReserve(1L, 1)).isTrue();
    }

    @Test
    void tryReserveDefersToDbWhenRedisThrows() {
        enableRedis(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
            .thenThrow(new RuntimeException("redis down"));

        assertThat(coordinator.tryReserve(1L, 1)).isTrue();
    }

    @Test
    void tryReserveBypassesWhenDisabled() {
        enableRedis(false);

        assertThat(coordinator.tryReserve(1L, 1)).isTrue();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void releaseSwallowsRedisErrors() {
        enableRedis(true);
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(any(), any(Long.class))).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> coordinator.release(1L, 1)).doesNotThrowAnyException();
    }

    @Test
    void releaseIncrementsCounterWhenEnabled() {
        enableRedis(true);
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        coordinator.release(1L, 3);

        verify(ops).increment("drop:1:avail", 3L);
    }

    @Test
    void releaseIsNoOpWhenDisabled() {
        enableRedis(false);

        coordinator.release(1L, 3);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void cacheAvailabilitySetsCounterWhenEnabled() {
        enableRedis(true);
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        coordinator.cacheAvailability(7L, 42);

        verify(ops).set(eq("drop:7:avail"), eq("42"), any(Duration.class));
    }

    @Test
    void cacheAvailabilityIsNoOpWhenDisabled() {
        enableRedis(false);

        coordinator.cacheAvailability(7L, 42);

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void cacheAvailabilitySwallowsRedisErrors() {
        enableRedis(true);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> coordinator.cacheAvailability(7L, 42)).doesNotThrowAnyException();
    }

    @Test
    void createHoldTtlKeySetsKeyWhenEnabled() {
        enableRedis(true);
        ValueOperations<String, String> ops = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        coordinator.createHoldTtlKey("hold-1", Duration.ofSeconds(120));

        verify(ops).set(eq("hold:hold-1"), eq("1"), eq(Duration.ofSeconds(120)));
    }

    @Test
    void createHoldTtlKeyIsNoOpWhenDisabled() {
        enableRedis(false);

        coordinator.createHoldTtlKey("hold-1", Duration.ofSeconds(120));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void createHoldTtlKeySwallowsRedisErrors() {
        enableRedis(true);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> coordinator.createHoldTtlKey("hold-1", Duration.ofSeconds(120)))
            .doesNotThrowAnyException();
    }
}
