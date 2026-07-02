package com.limiteddrop.reservation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limiteddrop.reservation.domain.OutboxEvent;
import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class OutboxServiceImplTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private OutboxPublisher outboxPublisher;

    @InjectMocks
    private OutboxServiceImpl service;

    @Test
    void enqueuePersistsPendingEventReadyForImmediateDelivery() {
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.enqueue("hold", "h1", "HoldCreated", "{}");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getEventType()).isEqualTo("HoldCreated");
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getNextAttemptAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPublishedAt()).isNull();
    }

    @Test
    void publishPendingDelegatesEachEligibleEventToPublisher() {
        OutboxEvent first = pending(1L, "HoldCreated");
        OutboxEvent second = pending(2L, "HoldConfirmed");
        when(outboxEventRepository.findPublishable(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(first, second));
        when(outboxPublisher.publishOne(1L)).thenReturn(true);
        when(outboxPublisher.publishOne(2L)).thenReturn(true);

        int published = service.publishPending();

        assertThat(published).isEqualTo(2);
        verify(outboxPublisher).publishOne(1L);
        verify(outboxPublisher).publishOne(2L);
    }

    @Test
    void publishPendingCountsOnlySuccessfulDeliveries() {
        OutboxEvent first = pending(1L, "HoldCreated");
        OutboxEvent second = pending(2L, "HoldConfirmed");
        when(outboxEventRepository.findPublishable(any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(first, second));
        when(outboxPublisher.publishOne(1L)).thenReturn(true);
        when(outboxPublisher.publishOne(2L)).thenReturn(false);

        int published = service.publishPending();

        // A poison sibling (id=2) does not prevent the healthy event (id=1) from being counted.
        assertThat(published).isEqualTo(1);
        verify(outboxPublisher).publishOne(eq(1L));
        verify(outboxPublisher).publishOne(eq(2L));
    }

    private OutboxEvent pending(Long id, String type) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setAggregateType("hold");
        event.setAggregateId("h" + id);
        event.setEventType(type);
        event.setPayload("{}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(0);
        event.setCreatedAt(Instant.now());
        event.setNextAttemptAt(Instant.now());
        return event;
    }
}
