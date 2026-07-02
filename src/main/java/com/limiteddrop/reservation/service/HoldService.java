package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.domain.Hold;
import com.limiteddrop.reservation.domain.Reservation;

public interface HoldService {

    Hold createHold(Long dropId, String userId, int quantity, String idempotencyKey);

    Reservation confirmHold(String holdId, String idempotencyKey);

    void cancelHold(String holdId);

    int expireDueHolds();
}
