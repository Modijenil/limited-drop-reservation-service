package com.limiteddrop.reservation.service.exception;

/**
 * Thrown when a concurrent request is already in flight for the same idempotency key.
 * Distinct from {@link ConflictException} so the API can return a retry-soon signal.
 */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String message) {
        super(message);
    }
}
