package com.limiteddrop.reservation.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void dropsExchangeIsDurableTopicExchange() {
        TopicExchange exchange = config.dropsExchange();

        assertThat(exchange.getName()).isEqualTo(RabbitConfig.EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    void auditQueueIsDurable() {
        Queue queue = config.auditQueue();

        assertThat(queue.getName()).isEqualTo(RabbitConfig.AUDIT_QUEUE);
        assertThat(queue.isDurable()).isTrue();
    }

    @Test
    void auditBindingRoutesHoldEventsToAuditQueue() {
        Queue queue = config.auditQueue();
        TopicExchange exchange = config.dropsExchange();

        Binding binding = config.auditBinding(queue, exchange);

        assertThat(binding.getDestination()).isEqualTo(RabbitConfig.AUDIT_QUEUE);
        assertThat(binding.getExchange()).isEqualTo(RabbitConfig.EXCHANGE);
        assertThat(binding.getRoutingKey()).isEqualTo("Hold*");
    }
}
