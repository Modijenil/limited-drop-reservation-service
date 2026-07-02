package com.limiteddrop.reservation.infra;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MaintenanceSchedulerTest {

    @Mock
    private HoldService holdService;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private MaintenanceScheduler scheduler;

    @Test
    void expireHoldsDelegatesToService() {
        when(holdService.expireDueHolds()).thenReturn(3);

        scheduler.expireHolds();

        verify(holdService).expireDueHolds();
    }

    @Test
    void expireHoldsHandlesZeroExpired() {
        when(holdService.expireDueHolds()).thenReturn(0);

        scheduler.expireHolds();

        verify(holdService).expireDueHolds();
    }

    @Test
    void publishOutboxDelegatesToService() {
        when(outboxService.publishPending()).thenReturn(5);

        scheduler.publishOutbox();

        verify(outboxService).publishPending();
    }

    @Test
    void publishOutboxHandlesZeroPublished() {
        when(outboxService.publishPending()).thenReturn(0);

        scheduler.publishOutbox();

        verify(outboxService).publishPending();
    }
}
