package com.limiteddrop.reservation.service;

import com.limiteddrop.reservation.domain.IdempotencyRecord;
import java.util.Optional;

public interface IdempotencyService {

    IdempotencyRecord createOrValidate(String key, String endpoint, String requestHash);

    Optional<IdempotencyReplay> findReplay(String key, String endpoint, String requestHash);

    void storeResponse(String key, int httpStatus, String responseBody);
}
