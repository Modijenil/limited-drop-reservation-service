package com.limiteddrop.reservation.api.dto;

import com.limiteddrop.reservation.domain.Reservation;
import java.math.BigDecimal;
import java.time.Instant;

public record ReservationResponse(
    Long id,
    String holdId,
    Long dropId,
    String userId,
    int quantity,
    BigDecimal totalAmount,
    String status,
    Instant confirmedAt
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
            reservation.getId(),
            reservation.getHoldId(),
            reservation.getDropId(),
            reservation.getUserId(),
            reservation.getQuantity(),
            reservation.getTotalAmount(),
            reservation.getStatus().name(),
            reservation.getConfirmedAt()
        );
    }
}
