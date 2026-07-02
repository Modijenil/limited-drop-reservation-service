package com.limiteddrop.reservation.infra;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class AuditEventConsumerTest {

    private final AuditEventConsumer consumer = new AuditEventConsumer();

    @Test
    void onEventConsumesPayloadWithoutError() {
        assertThatCode(() -> consumer.onEvent("{\"type\":\"HoldCreated\"}"))
            .doesNotThrowAnyException();
    }
}
