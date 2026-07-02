package com.limiteddrop.reservation.infra;

import com.limiteddrop.reservation.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuditEventConsumer {

    @RabbitListener(queues = RabbitConfig.AUDIT_QUEUE)
    public void onEvent(String payload) {
        log.info("AUDIT_EVENT payload={}", payload);
    }
}
