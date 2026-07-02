package com.limiteddrop.reservation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limiteddrop.reservation.domain.IdempotencyRecord;
import com.limiteddrop.reservation.repository.IdempotencyRecordRepository;
import com.limiteddrop.reservation.service.IdempotencyReplay;
import com.limiteddrop.reservation.service.exception.ConflictException;
import com.limiteddrop.reservation.service.exception.RequestInProgressException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @InjectMocks
    private IdempotencyServiceImpl service;

    @Test
    void createOrValidateRegistersNewKey() {
        when(repository.findById("k1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyRecord record = service.createOrValidate("k1", "POST:/holds", "hash-1");

        assertThat(record.getIdempotencyKey()).isEqualTo("k1");
        assertThat(record.getEndpoint()).isEqualTo("POST:/holds");
        assertThat(record.getHttpStatus()).isZero();
    }

    @Test
    void createOrValidateReturnsCompletedRecordForSameKeySamePayload() {
        IdempotencyRecord existing = record("k1", "POST:/holds", "hash-1", 201, "{}");
        when(repository.findById("k1")).thenReturn(Optional.of(existing));

        IdempotencyRecord result = service.createOrValidate("k1", "POST:/holds", "hash-1");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createOrValidateSignalsInProgressForConcurrentSameKey() {
        IdempotencyRecord inProgress = record("k1", "POST:/holds", "hash-1", 0, null);
        when(repository.findById("k1")).thenReturn(Optional.of(inProgress));

        assertThatThrownBy(() -> service.createOrValidate("k1", "POST:/holds", "hash-1"))
            .isInstanceOf(RequestInProgressException.class);
    }

    @Test
    void createOrValidateHandlesInsertRaceAsInProgress() {
        // First read sees no record, so we attempt an insert; a concurrent writer wins the unique key.
        when(repository.findById("k1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IdempotencyRecord.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.createOrValidate("k1", "POST:/holds", "hash-1"))
            .isInstanceOf(RequestInProgressException.class);
    }

    @Test
    void createOrValidateRejectsSameKeyDifferentPayload() {
        IdempotencyRecord existing = record("k1", "POST:/holds", "hash-1", 0, null);
        when(repository.findById("k1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createOrValidate("k1", "POST:/holds", "hash-DIFFERENT"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void findReplayReturnsStoredResponseWhenComplete() {
        IdempotencyRecord existing = record("k1", "POST:/holds", "hash-1", 201, "{\"id\":\"abc\"}");
        when(repository.findById("k1")).thenReturn(Optional.of(existing));

        Optional<IdempotencyReplay> replay = service.findReplay("k1", "POST:/holds", "hash-1");

        assertThat(replay).isPresent();
        assertThat(replay.get().httpStatus()).isEqualTo(201);
        assertThat(replay.get().responseBody()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    void findReplayEmptyWhenResponseNotYetStored() {
        IdempotencyRecord existing = record("k1", "POST:/holds", "hash-1", 0, null);
        when(repository.findById("k1")).thenReturn(Optional.of(existing));

        assertThat(service.findReplay("k1", "POST:/holds", "hash-1")).isEmpty();
    }

    @Test
    void findReplayRejectsKeyReuseWithDifferentPayload() {
        IdempotencyRecord existing = record("k1", "POST:/holds", "hash-1", 201, "{}");
        when(repository.findById("k1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.findReplay("k1", "POST:/holds", "other-hash"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void storeResponsePersistsBodyAndStatus() {
        IdempotencyRecord existing = record("k1", "POST:/holds", "hash-1", 0, null);
        when(repository.findById("k1")).thenReturn(Optional.of(existing));
        when(repository.save(any(IdempotencyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.storeResponse("k1", 201, "{\"id\":\"abc\"}");

        assertThat(existing.getHttpStatus()).isEqualTo(201);
        assertThat(existing.getResponseBody()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    void storeResponseFailsWhenKeyMissing() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.storeResponse("missing", 200, "{}"))
            .isInstanceOf(ConflictException.class);
    }

    private IdempotencyRecord record(String key, String endpoint, String hash, int status, String body) {
        IdempotencyRecord r = new IdempotencyRecord();
        r.setIdempotencyKey(key);
        r.setEndpoint(endpoint);
        r.setRequestHash(hash);
        r.setHttpStatus(status);
        r.setResponseBody(body);
        r.setCreatedAt(Instant.now());
        r.setExpiresAt(Instant.now().plusSeconds(3600));
        return r;
    }
}
