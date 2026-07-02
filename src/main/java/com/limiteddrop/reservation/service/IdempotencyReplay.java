package com.limiteddrop.reservation.service;

public record IdempotencyReplay(
    int httpStatus,
    String responseBody
) {
}
