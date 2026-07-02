package com.limiteddrop.reservation.api.dto;

import com.limiteddrop.reservation.domain.Drop;
import java.math.BigDecimal;
import java.time.Instant;

public record DropResponse(
    Long id,
    String name,
    String description,
    String status,
    Instant opensAt,
    Instant closesAt,
    BigDecimal unitPrice,
    String currency,
    int totalUnits,
    int availableUnits,
    int heldUnits,
    int confirmedUnits
) {
    public static DropResponse from(Drop d) {
        return new DropResponse(
            d.getId(),
            d.getName(),
            d.getDescription(),
            d.getStatus().name(),
            d.getOpensAt(),
            d.getClosesAt(),
            d.getUnitPrice(),
            d.getCurrency(),
            d.getTotalUnits(),
            d.getAvailableUnits(),
            d.getHeldUnits(),
            d.getConfirmedUnits()
        );
    }
}
