package com.limiteddrop.reservation.api.dto;

import com.limiteddrop.reservation.domain.Hold;
import java.time.Instant;

public record HoldResponse(
    String id,
    Long dropId,
    String userId,
    int quantity,
    String status,
    Instant expiresAt
) {
    public static HoldResponse from(Hold hold) {
        return new HoldResponse(
            hold.getId(),
            hold.getDropId(),
            hold.getUserId(),
            hold.getQuantity(),
            hold.getStatus().name(),
            hold.getExpiresAt()
        );
    }
}
