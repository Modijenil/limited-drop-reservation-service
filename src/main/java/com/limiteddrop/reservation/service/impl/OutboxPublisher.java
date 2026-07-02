package com.limiteddrop.reservation.service.impl;

import com.limiteddrop.reservation.domain.OutboxEvent;
import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a single outbox event in its own transaction so that one failing (poison) event
 * cannot roll back siblings that were already delivered in the same relay cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    public static final String EXCHANGE = "drops.events";

    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${app.outbox.max-attempts:8}")
    private int maxAttempts;

    @Value("${app.outbox.backoff-base-seconds:1}")
    private long backoffBaseSeconds;

    @Value("${app.outbox.backoff-cap-seconds:300}")
    private long backoffCapSeconds;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishOne(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null || event.getStatus() != OutboxStatus.PENDING) {
            // Another relay instance already handled it (or it moved to a terminal state).
            return false;
        }

        try {
            rabbitTemplate.convertAndSend(EXCHANGE, event.getEventType(), event.getPayload());
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setNextAttemptAt(null);
            outboxEventRepository.save(event);
            return true;
        } catch (Exception ex) {
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);
            event.setLastError(truncate(ex.getMessage()));
            if (attempts >= maxAttempts) {
                event.setStatus(OutboxStatus.DEAD);
                event.setNextAttemptAt(null);
                log.error("Outbox event {} exhausted {} attempts; marking DEAD", eventId, attempts, ex);
            } else {
                Instant next = Instant.now().plusSeconds(backoffSeconds(attempts));
                event.setNextAttemptAt(next);
                log.warn("Outbox event {} publish attempt {} failed; retry after {}", eventId, attempts, next);
            }
            outboxEventRepository.save(event);
            return false;
        }
    }

    private long backoffSeconds(int attempts) {
        int shift = Math.min(attempts - 1, 16);
        long scaled = backoffBaseSeconds << shift;
        if (scaled < 0) {
            return backoffCapSeconds;
        }
        return Math.min(scaled, backoffCapSeconds);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
