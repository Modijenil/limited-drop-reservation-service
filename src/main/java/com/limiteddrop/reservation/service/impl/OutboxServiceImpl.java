package com.limiteddrop.reservation.service.impl;

import com.limiteddrop.reservation.domain.OutboxEvent;
import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import com.limiteddrop.reservation.service.OutboxService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    public static final String EXCHANGE = OutboxPublisher.EXCHANGE;

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxPublisher outboxPublisher;

    @Override
    @Transactional
    public void enqueue(String aggregateType, String aggregateId, String eventType, String payload) {
        Instant now = Instant.now();
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType(aggregateType);
        outboxEvent.setAggregateId(aggregateId);
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(payload);
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setAttempts(0);
        outboxEvent.setNextAttemptAt(now);
        outboxEvent.setCreatedAt(now);
        outboxEventRepository.save(outboxEvent);
    }

    @Override
    public int publishPending() {
        List<OutboxEvent> eligible =
            outboxEventRepository.findPublishable(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        int published = 0;
        for (OutboxEvent event : eligible) {
            // Each event is published in its own transaction; a poison event cannot roll back siblings.
            if (outboxPublisher.publishOne(event.getId())) {
                published++;
            }
        }
        return published;
    }
}
