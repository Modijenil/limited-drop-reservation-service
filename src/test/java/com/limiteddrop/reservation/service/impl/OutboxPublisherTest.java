package com.limiteddrop.reservation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limiteddrop.reservation.domain.OutboxEvent;
import com.limiteddrop.reservation.domain.OutboxStatus;
import com.limiteddrop.reservation.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OutboxPublisher publisher;

    @BeforeEach
    void configureBackoff() {
        ReflectionTestUtils.setField(publisher, "maxAttempts", 8);
        ReflectionTestUtils.setField(publisher, "backoffBaseSeconds", 1L);
        ReflectionTestUtils.setField(publisher, "backoffCapSeconds", 300L);
    }

    @Test
    void publishOneMarksEventPublishedOnSuccess() {
        OutboxEvent event = pending(1L, 0);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = publisher.publishOne(1L);

        assertThat(result).isTrue();
        verify(rabbitTemplate).convertAndSend(eq(OutboxPublisher.EXCHANGE), eq("HoldCreated"), eq("{}"));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void publishOneSchedulesBackoffRetryWhenBrokerFails() {
        OutboxEvent event = pending(2L, 0);
        when(outboxEventRepository.findById(2L)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new AmqpException("broker down"))
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        boolean result = publisher.publishOne(2L);

        assertThat(result).isFalse();
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isNotNull();
        assertThat(event.getLastError()).contains("broker down");
    }

    @Test
    void publishOneMarksEventDeadAfterExhaustingAttempts() {
        OutboxEvent event = pending(3L, 7); // one more failure reaches maxAttempts (8)
        when(outboxEventRepository.findById(3L)).thenReturn(Optional.of(event));
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new AmqpException("still down"))
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        boolean result = publisher.publishOne(3L);

        assertThat(result).isFalse();
        assertThat(event.getAttempts()).isEqualTo(8);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void publishOneSkipsMissingEvent() {
        when(outboxEventRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(publisher.publishOne(99L)).isFalse();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
    }

    @Test
    void publishOneSkipsAlreadyPublishedEvent() {
        OutboxEvent event = pending(4L, 0);
        event.setStatus(OutboxStatus.PUBLISHED);
        when(outboxEventRepository.findById(4L)).thenReturn(Optional.of(event));

        assertThat(publisher.publishOne(4L)).isFalse();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).save(any());
    }

    private OutboxEvent pending(Long id, int attempts) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setAggregateType("hold");
        event.setAggregateId("h" + id);
        event.setEventType("HoldCreated");
        event.setPayload("{}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(attempts);
        event.setCreatedAt(Instant.now());
        event.setNextAttemptAt(Instant.now());
        return event;
    }
}
