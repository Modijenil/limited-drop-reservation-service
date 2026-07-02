package com.limiteddrop.reservation.service.impl;

import com.limiteddrop.reservation.domain.IdempotencyRecord;
import com.limiteddrop.reservation.repository.IdempotencyRecordRepository;
import com.limiteddrop.reservation.service.IdempotencyReplay;
import com.limiteddrop.reservation.service.IdempotencyService;
import com.limiteddrop.reservation.service.exception.ConflictException;
import com.limiteddrop.reservation.service.exception.RequestInProgressException;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyRecordRepository repository;

    @Override
    @Transactional
    public IdempotencyRecord createOrValidate(String key, String endpoint, String requestHash) {
        Optional<IdempotencyRecord> found = repository.findById(key);
        if (found.isPresent()) {
            return validateExisting(found.get(), endpoint, requestHash);
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setEndpoint(endpoint);
        record.setRequestHash(requestHash);
        record.setHttpStatus(0);
        record.setCreatedAt(Instant.now());
        record.setExpiresAt(Instant.now().plusSeconds(3600));
        try {
            return repository.saveAndFlush(record);
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent request committed this key between our read and write; their request is
            // still in flight (its canonical response is not yet stored). Signal the client to retry.
            // Note: we deliberately do NOT re-read here — the failed flush has marked the current
            // Hibernate session rollback-only, so any further query in this transaction is unsafe.
            throw new RequestInProgressException("Request already in progress for idempotency key");
        }
    }

    private IdempotencyRecord validateExisting(IdempotencyRecord existing, String endpoint, String requestHash) {
        if (!existing.getEndpoint().equals(endpoint) || !existing.getRequestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency key reused with different request");
        }
        boolean completed = existing.getHttpStatus() != null && existing.getHttpStatus() > 0;
        if (!completed) {
            // A first request with this key is still being processed; signal the client to retry shortly.
            throw new RequestInProgressException("Request already in progress for idempotency key");
        }
        return existing;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyReplay> findReplay(String key, String endpoint, String requestHash) {
        return repository.findById(key)
            .map(existing -> {
                if (!existing.getEndpoint().equals(endpoint) || !existing.getRequestHash().equals(requestHash)) {
                    throw new ConflictException("Idempotency key reused with different request");
                }
                return existing;
            })
            .filter(existing -> existing.getHttpStatus() != null && existing.getHttpStatus() > 0 && existing.getResponseBody() != null)
            .map(existing -> new IdempotencyReplay(existing.getHttpStatus(), existing.getResponseBody()));
    }

    @Override
    @Transactional
    public void storeResponse(String key, int httpStatus, String responseBody) {
        IdempotencyRecord record = repository.findById(key)
            .orElseThrow(() -> new ConflictException("Idempotency key not registered: " + key));

        record.setHttpStatus(httpStatus);
        record.setResponseBody(responseBody);
        repository.save(record);
    }
}
