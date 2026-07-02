package com.limiteddrop.reservation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.domain.Hold;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.domain.Reservation;
import com.limiteddrop.reservation.infra.RedisInventoryCoordinator;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.repository.HoldRepository;
import com.limiteddrop.reservation.repository.ReservationRepository;
import com.limiteddrop.reservation.service.IdempotencyService;
import com.limiteddrop.reservation.service.OutboxService;
import com.limiteddrop.reservation.service.exception.ConflictException;
import com.limiteddrop.reservation.service.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HoldServiceImplTest {

    @Mock
    private DropRepository dropRepository;
    @Mock
    private HoldRepository holdRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private OutboxService outboxService;
    @Mock
    private RedisInventoryCoordinator redisInventoryCoordinator;

    @InjectMocks
    private HoldServiceImpl holdService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(holdService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(holdService, "holdTtlSeconds", 120L);
    }

    // ---------- createHold: happy ----------

    @Test
    void createHoldReservesInventoryWithoutOversell() {
        Drop drop = openDrop(1L, 10);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));
        when(redisInventoryCoordinator.tryReserve(1L, 2)).thenReturn(true);
        when(dropRepository.reserveInventory(1L, 2)).thenReturn(1);
        when(holdRepository.saveAndFlush(any(Hold.class))).thenAnswer(inv -> inv.getArgument(0));

        Hold result = holdService.createHold(1L, "user-1", 2, "idem-1");

        assertThat(result.getDropId()).isEqualTo(1L);
        assertThat(result.getQuantity()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        verify(outboxService).enqueue(any(), any(), any(), any());
    }

    @Test
    void createHoldReturnsExistingHoldWhenIdempotencyKeyAlreadyUsed() {
        Hold existing = Hold.create(1L, "user-1", 2, Instant.now().plusSeconds(60), "idem-dup");
        when(holdRepository.findByIdempotencyKey("idem-dup")).thenReturn(Optional.of(existing));

        Hold result = holdService.createHold(1L, "user-1", 2, "idem-dup");

        assertThat(result).isSameAs(existing);
        verify(dropRepository, never()).reserveInventory(anyLong(), anyInt());
        verify(holdRepository, never()).saveAndFlush(any());
    }

    // ---------- createHold: negative ----------

    @Test
    void createHoldFailsWhenInventoryInsufficient() {
        Drop drop = openDrop(1L, 1);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));
        when(redisInventoryCoordinator.tryReserve(1L, 2)).thenReturn(true);
        when(dropRepository.reserveInventory(1L, 2)).thenReturn(0);

        assertThatThrownBy(() -> holdService.createHold(1L, "user-1", 2, "idem-2"))
            .isInstanceOf(ConflictException.class);

        verify(holdRepository, never()).saveAndFlush(any());
        verify(redisInventoryCoordinator).release(1L, 2);
    }

    @Test
    void createHoldFailsWhenRedisFastPathRejects() {
        Drop drop = openDrop(1L, 5);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));
        when(redisInventoryCoordinator.tryReserve(1L, 2)).thenReturn(false);

        assertThatThrownBy(() -> holdService.createHold(1L, "user-1", 2, "idem-3"))
            .isInstanceOf(ConflictException.class);

        verify(dropRepository, never()).reserveInventory(anyLong(), anyInt());
        verify(holdRepository, never()).saveAndFlush(any());
    }

    @Test
    void createHoldFailsWhenDropMissing() {
        when(dropRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.createHold(99L, "user-1", 1, "idem-4"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createHoldFailsWhenDropNotOpen() {
        Drop drop = openDrop(1L, 5);
        drop.setStatus(DropStatus.CLOSED);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));

        assertThatThrownBy(() -> holdService.createHold(1L, "user-1", 1, "idem-5"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void createHoldFailsWhenBeforeOpensAt() {
        Drop drop = openDrop(1L, 5);
        drop.setOpensAt(Instant.now().plusSeconds(3600));
        drop.setClosesAt(Instant.now().plusSeconds(7200));
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));

        assertThatThrownBy(() -> holdService.createHold(1L, "user-1", 1, "idem-6"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void createHoldDuplicateInFlightKeyReleasesInventoryAndConflicts() {
        Drop drop = openDrop(1L, 5);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));
        when(redisInventoryCoordinator.tryReserve(1L, 1)).thenReturn(true);
        when(dropRepository.reserveInventory(1L, 1)).thenReturn(1);
        when(holdRepository.saveAndFlush(any(Hold.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate idempotency_key"));

        assertThatThrownBy(() -> holdService.createHold(1L, "user-1", 1, "idem-race"))
            .isInstanceOf(ConflictException.class);

        // Inventory leak protection: Redis counter is returned even though the DB tx will roll back.
        verify(redisInventoryCoordinator).release(1L, 1);
    }

    // ---------- confirmHold ----------

    @Test
    void confirmHoldCreatesReservationExactlyOnce() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().plusSeconds(60), "idem-hold");
        Drop drop = openDrop(1L, 10);
        drop.setUnitPrice(new BigDecimal("100.00"));

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(reservationRepository.findByHoldId(hold.getId())).thenReturn(Optional.empty());
        when(holdRepository.markConfirmed(hold.getId())).thenReturn(1);
        when(dropRepository.confirmInventory(1L, 2)).thenReturn(1);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(99L);
            return r;
        });

        Reservation reservation = holdService.confirmHold(hold.getId(), "idem-confirm");

        assertThat(reservation.getId()).isEqualTo(99L);
        assertThat(reservation.getTotalAmount()).isEqualByComparingTo("200.00");
        verify(outboxService).enqueue(any(), any(), any(), any());
    }

    @Test
    void confirmHoldReturnsExistingReservationOnRetry() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().plusSeconds(60), "idem-hold");
        Reservation existing = new Reservation();
        existing.setId(7L);

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(reservationRepository.findByHoldId(hold.getId())).thenReturn(Optional.of(existing));

        Reservation result = holdService.confirmHold(hold.getId(), "idem-confirm");

        assertThat(result).isSameAs(existing);
        verify(holdRepository, never()).markConfirmed(anyString());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void confirmHoldRejectsExpiredByTime() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().minusSeconds(1), "idem-hold");

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(reservationRepository.findByHoldId(hold.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.confirmHold(hold.getId(), "idem-confirm"))
            .isInstanceOf(ConflictException.class);

        verify(holdRepository, never()).markConfirmed(anyString());
        verify(dropRepository, never()).confirmInventory(anyLong(), anyInt());
    }

    @Test
    void confirmHoldRejectsCancelled() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().plusSeconds(60), "idem-hold");
        hold.setStatus(HoldStatus.CANCELLED);

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(reservationRepository.findByHoldId(hold.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.confirmHold(hold.getId(), "idem-confirm"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void confirmHoldRejectsWhenLostRaceToExpirySweep() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().plusSeconds(60), "idem-hold");

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(reservationRepository.findByHoldId(hold.getId())).thenReturn(Optional.empty());
        when(holdRepository.markConfirmed(hold.getId())).thenReturn(0);

        assertThatThrownBy(() -> holdService.confirmHold(hold.getId(), "idem-confirm"))
            .isInstanceOf(ConflictException.class);

        verify(dropRepository, never()).confirmInventory(anyLong(), anyInt());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void confirmHoldNotFound() {
        when(holdRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.confirmHold("missing", "idem-confirm"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---------- cancelHold ----------

    @Test
    void cancelHoldReleasesUnits() {
        Hold hold = Hold.create(1L, "user-1", 3, Instant.now().plusSeconds(60), "idem-hold");

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holdRepository.markCancelled(hold.getId())).thenReturn(1);
        when(dropRepository.releaseInventory(1L, 3)).thenReturn(1);

        holdService.cancelHold(hold.getId());

        verify(dropRepository).releaseInventory(1L, 3);
        verify(redisInventoryCoordinator).release(1L, 3);
        verify(outboxService).enqueue(any(), any(), any(), any());
    }

    @Test
    void cancelHoldAlreadyCancelledIsNoOp() {
        Hold hold = Hold.create(1L, "user-1", 3, Instant.now().plusSeconds(60), "idem-hold");
        hold.setStatus(HoldStatus.CANCELLED);

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));

        holdService.cancelHold(hold.getId());

        verify(holdRepository, never()).markCancelled(anyString());
        verify(dropRepository, never()).releaseInventory(anyLong(), anyInt());
    }

    @Test
    void cancelHoldConfirmedRejected() {
        Hold hold = Hold.create(1L, "user-1", 3, Instant.now().plusSeconds(60), "idem-hold");
        hold.setStatus(HoldStatus.CONFIRMED);

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.cancelHold(hold.getId()))
            .isInstanceOf(ConflictException.class);

        verify(dropRepository, never()).releaseInventory(anyLong(), anyInt());
    }

    @Test
    void cancelHoldLostRaceToExpiryIsNoOpWithoutDoubleRelease() {
        Hold hold = Hold.create(1L, "user-1", 3, Instant.now().plusSeconds(60), "idem-hold");

        when(holdRepository.findById(hold.getId())).thenReturn(Optional.of(hold));
        when(holdRepository.markCancelled(hold.getId())).thenReturn(0);

        holdService.cancelHold(hold.getId());

        // Concurrent expiry already returned the units; cancel must not release again.
        verify(dropRepository, never()).releaseInventory(anyLong(), anyInt());
        verify(redisInventoryCoordinator, never()).release(anyLong(), anyInt());
    }

    @Test
    void cancelHoldNotFound() {
        when(holdRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.cancelHold("missing"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---------- expireDueHolds ----------

    @Test
    void expireDueHoldsReleasesUnitsExactlyOnce() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().minusSeconds(5), "idem-hold");

        when(holdRepository.findTop200ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
            .thenReturn(List.of(hold));
        when(holdRepository.markExpired(hold.getId())).thenReturn(1);
        when(dropRepository.releaseInventory(1L, 2)).thenReturn(1);

        int expired = holdService.expireDueHolds();

        assertThat(expired).isEqualTo(1);
        verify(dropRepository).releaseInventory(1L, 2);
        verify(redisInventoryCoordinator).release(1L, 2);
        verify(outboxService).enqueue(any(), any(), any(), any());
    }

    @Test
    void expireDueHoldsSkipsHoldAlreadyTransitionedConcurrently() {
        Hold hold = Hold.create(1L, "user-1", 2, Instant.now().minusSeconds(5), "idem-hold");

        when(holdRepository.findTop200ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
            .thenReturn(List.of(hold));
        when(holdRepository.markExpired(hold.getId())).thenReturn(0);

        int expired = holdService.expireDueHolds();

        assertThat(expired).isEqualTo(0);
        verify(dropRepository, never()).releaseInventory(anyLong(), anyInt());
        verify(redisInventoryCoordinator, never()).release(anyLong(), anyInt());
    }

    // ---------- concurrency ----------

    @Test
    void createHoldLastUnitRaceAllowsSingleWinner() throws Exception {
        Drop drop = openDrop(1L, 1);
        when(dropRepository.findById(1L)).thenReturn(Optional.of(drop));
        when(redisInventoryCoordinator.tryReserve(anyLong(), anyInt())).thenReturn(true);
        when(holdRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(holdRepository.saveAndFlush(any(Hold.class))).thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger reserveCalls = new AtomicInteger();
        when(dropRepository.reserveInventory(1L, 1)).thenAnswer(inv -> reserveCalls.incrementAndGet() == 1 ? 1 : 0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            tasks.add(() -> runCreateHold("u1", "idem-u1"));
            tasks.add(() -> runCreateHold("u2", "idem-u2"));

            List<Future<Boolean>> futures = pool.invokeAll(tasks);

            int successCount = 0;
            int failCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successCount++;
                } else {
                    failCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(failCount).isEqualTo(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private boolean runCreateHold(String userId, String idemKey) {
        try {
            holdService.createHold(1L, userId, 1, idemKey);
            return true;
        } catch (ConflictException ex) {
            return false;
        }
    }

    private Drop openDrop(Long id, int availableUnits) {
        Drop drop = new Drop();
        drop.setId(id);
        drop.setStatus(DropStatus.OPEN);
        drop.setOpensAt(Instant.now().minusSeconds(60));
        drop.setClosesAt(Instant.now().plusSeconds(3600));
        drop.setAvailableUnits(availableUnits);
        drop.setHeldUnits(0);
        drop.setConfirmedUnits(0);
        drop.setUnitPrice(new BigDecimal("1.00"));
        drop.setCurrency("USD");
        drop.setTotalUnits(availableUnits);
        return drop;
    }
}
