package com.limiteddrop.reservation.infra;

import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceScheduler {

    private final HoldService holdService;
    private final OutboxService outboxService;

    @Scheduled(fixedDelayString = "1000")
    public void expireHolds() {
        int expired = holdService.expireDueHolds();
        if (expired > 0) {
            log.info("Expired {} holds", expired);
        }
    }

    @Scheduled(fixedDelayString = "1000")
    public void publishOutbox() {
        int published = outboxService.publishPending();
        if (published > 0) {
            log.debug("Published {} outbox events", published);
        }
    }
}
