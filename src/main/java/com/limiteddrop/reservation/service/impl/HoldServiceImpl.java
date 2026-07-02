package com.limiteddrop.reservation.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.domain.Hold;
import com.limiteddrop.reservation.domain.HoldStatus;
import com.limiteddrop.reservation.domain.Reservation;
import com.limiteddrop.reservation.domain.ReservationStatus;
import com.limiteddrop.reservation.infra.RedisInventoryCoordinator;
import com.limiteddrop.reservation.repository.DropRepository;
import com.limiteddrop.reservation.repository.HoldRepository;
import com.limiteddrop.reservation.repository.ReservationRepository;
import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.service.IdempotencyService;
import com.limiteddrop.reservation.service.OutboxService;
import com.limiteddrop.reservation.service.exception.ConflictException;
import com.limiteddrop.reservation.service.exception.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HoldServiceImpl implements HoldService {

    private static final String CREATE_HOLD_ENDPOINT = "POST:/holds";
    private static final String CONFIRM_HOLD_ENDPOINT = "POST:/holds/{id}/confirm";

    private final DropRepository dropRepository;
    private final HoldRepository holdRepository;
    private final ReservationRepository reservationRepository;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final RedisInventoryCoordinator redisInventoryCoordinator;
    private final ObjectMapper objectMapper;

    @Value("${app.hold.ttl-seconds:120}")
    private long holdTtlSeconds;

    @Override
    @Transactional
    public Hold createHold(Long dropId, String userId, int quantity, String idempotencyKey) {
        String requestHash = dropId + ":" + userId + ":" + quantity;
        idempotencyService.createOrValidate(idempotencyKey, CREATE_HOLD_ENDPOINT, requestHash);

        Hold existing = holdRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return existing;
        }

        Drop drop = dropRepository.findById(dropId)
            .orElseThrow(() -> new NotFoundException("Drop not found: " + dropId));

        Instant now = Instant.now();
        if (drop.getStatus() != DropStatus.OPEN || now.isBefore(drop.getOpensAt()) || now.isAfter(drop.getClosesAt())) {
            throw new ConflictException("Drop is not open");
        }

        boolean redisReserved = redisInventoryCoordinator.tryReserve(dropId, quantity);
        if (!redisReserved) {
            throw new ConflictException("Not enough inventory");
        }

        int updated = dropRepository.reserveInventory(dropId, quantity);
        if (updated == 0) {
            redisInventoryCoordinator.release(dropId, quantity);
            throw new ConflictException("Not enough inventory");
        }

        Instant expiresAt = now.plusSeconds(holdTtlSeconds);
        Hold hold = Hold.create(dropId, userId, quantity, expiresAt, idempotencyKey);
        Hold saved;
        try {
            saved = holdRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException duplicate) {
            // Two concurrent requests shared the same idempotency key; the loser must not leak inventory.
            // The DB reservation decrement is rolled back with this transaction; release the Redis counter explicitly.
            redisInventoryCoordinator.release(dropId, quantity);
            throw new ConflictException("Duplicate hold request for idempotency key: " + idempotencyKey);
        }
        redisInventoryCoordinator.createHoldTtlKey(saved.getId(), Duration.ofSeconds(holdTtlSeconds));

        outboxService.enqueue("hold", saved.getId(), "HoldCreated", toJson(Map.of(
            "holdId", saved.getId(),
            "dropId", saved.getDropId(),
            "userId", saved.getUserId(),
            "quantity", saved.getQuantity(),
            "expiresAt", saved.getExpiresAt().toString()
        )));

        return saved;
    }

    @Override
    @Transactional
    public Reservation confirmHold(String holdId, String idempotencyKey) {
        Hold hold = holdRepository.findById(holdId)
            .orElseThrow(() -> new NotFoundException("Hold not found: " + holdId));

        String requestHash = holdId;
        idempotencyService.createOrValidate(idempotencyKey, CONFIRM_HOLD_ENDPOINT, requestHash);

        Reservation existingReservation = reservationRepository.findByHoldId(holdId).orElse(null);
        if (existingReservation != null) {
            return existingReservation;
        }

        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            throw new ConflictException("Hold already confirmed but reservation is missing");
        }

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new ConflictException("Hold cannot be confirmed in status: " + hold.getStatus());
        }

        if (hold.getExpiresAt().isBefore(Instant.now())) {
            throw new ConflictException("Hold has expired");
        }

        // Atomic guarded transition: only one of {confirm, expiry-sweep, cancel} can flip an ACTIVE hold.
        int marked = holdRepository.markConfirmed(hold.getId());
        if (marked == 0) {
            throw new ConflictException("Hold is no longer active and cannot be confirmed");
        }

        int updated = dropRepository.confirmInventory(hold.getDropId(), hold.getQuantity());
        if (updated == 0) {
            throw new ConflictException("Inventory state invalid for confirmation");
        }

        Drop drop = dropRepository.findById(hold.getDropId())
            .orElseThrow(() -> new NotFoundException("Drop not found: " + hold.getDropId()));

        Reservation reservation = new Reservation();
        reservation.setHoldId(hold.getId());
        reservation.setDropId(hold.getDropId());
        reservation.setUserId(hold.getUserId());
        reservation.setQuantity(hold.getQuantity());
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setConfirmedAt(Instant.now());
        reservation.setTotalAmount(drop.getUnitPrice().multiply(java.math.BigDecimal.valueOf(hold.getQuantity())));

        Reservation saved = reservationRepository.save(reservation);

        outboxService.enqueue("hold", hold.getId(), "HoldConfirmed", toJson(Map.of(
            "holdId", hold.getId(),
            "reservationId", saved.getId(),
            "dropId", hold.getDropId(),
            "quantity", hold.getQuantity()
        )));

        return saved;
    }

    @Override
    @Transactional
    public void cancelHold(String holdId) {
        Hold hold = holdRepository.findById(holdId)
            .orElseThrow(() -> new NotFoundException("Hold not found: " + holdId));

        if (hold.getStatus() == HoldStatus.CANCELLED || hold.getStatus() == HoldStatus.EXPIRED) {
            return;
        }

        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            throw new ConflictException("Cannot cancel a confirmed hold");
        }

        // Atomic guarded transition: if a concurrent expiry already flipped the hold, treat cancel as a no-op
        // so inventory is never returned twice.
        int marked = holdRepository.markCancelled(hold.getId());
        if (marked == 0) {
            return;
        }

        int updated = dropRepository.releaseInventory(hold.getDropId(), hold.getQuantity());
        if (updated == 0) {
            throw new ConflictException("Inventory state invalid for cancellation");
        }

        redisInventoryCoordinator.release(hold.getDropId(), hold.getQuantity());

        outboxService.enqueue("hold", hold.getId(), "HoldCancelled", toJson(Map.of(
            "holdId", hold.getId(),
            "dropId", hold.getDropId(),
            "quantity", hold.getQuantity()
        )));
    }

    @Override
    @Transactional
    public int expireDueHolds() {
        int expiredCount = 0;
        for (Hold hold : holdRepository.findTop200ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(HoldStatus.ACTIVE, Instant.now())) {
            // Atomic guarded transition: skip holds a concurrent confirm/cancel already moved off ACTIVE,
            // guaranteeing units are released exactly once.
            int marked = holdRepository.markExpired(hold.getId());
            if (marked == 0) {
                continue;
            }

            int updated = dropRepository.releaseInventory(hold.getDropId(), hold.getQuantity());
            if (updated == 0) {
                continue;
            }

            redisInventoryCoordinator.release(hold.getDropId(), hold.getQuantity());
            outboxService.enqueue("hold", hold.getId(), "HoldExpired", toJson(Map.of(
                "holdId", hold.getId(),
                "dropId", hold.getDropId(),
                "quantity", hold.getQuantity()
            )));
            expiredCount++;
        }
        return expiredCount;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}
