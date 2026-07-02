package com.limiteddrop.reservation.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "drops.events";
    public static final String AUDIT_QUEUE = "drops.audit";
    public static final String DLX = "drops.events.dlx";
    public static final String AUDIT_DLQ = "drops.audit.dlq";

    @Bean
    TopicExchange dropsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue auditQueue() {
        // Messages the consumer rejects (after listener retries are exhausted) are routed to the DLX.
        return QueueBuilder.durable(AUDIT_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX)
            .build();
    }

    @Bean
    Binding auditBinding(Queue auditQueue, TopicExchange dropsExchange) {
        return BindingBuilder.bind(auditQueue).to(dropsExchange).with("Hold*");
    }

    @Bean
    FanoutExchange auditDeadLetterExchange() {
        return new FanoutExchange(DLX, true, false);
    }

    @Bean
    Queue auditDeadLetterQueue() {
        return QueueBuilder.durable(AUDIT_DLQ).build();
    }

    @Bean
    Binding auditDeadLetterBinding(Queue auditDeadLetterQueue, FanoutExchange auditDeadLetterExchange) {
        return BindingBuilder.bind(auditDeadLetterQueue).to(auditDeadLetterExchange);
    }
}
