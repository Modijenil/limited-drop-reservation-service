package com.limiteddrop.reservation.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateHoldRequest(
    @NotNull Long dropId,
    @NotBlank String userId,
    @Min(1) int quantity
) {
}
