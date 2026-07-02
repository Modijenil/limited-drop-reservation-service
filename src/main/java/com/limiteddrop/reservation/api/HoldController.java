package com.limiteddrop.reservation.api;

import com.limiteddrop.reservation.api.dto.CreateHoldRequest;
import com.limiteddrop.reservation.api.dto.HoldResponse;
import com.limiteddrop.reservation.api.dto.ReservationResponse;
import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.service.IdempotencyReplay;
import com.limiteddrop.reservation.service.IdempotencyService;
import com.limiteddrop.reservation.service.exception.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/holds")
@RequiredArgsConstructor
public class HoldController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CREATE_HOLD_ENDPOINT = "POST:/holds";
    private static final String CONFIRM_HOLD_ENDPOINT = "POST:/holds/{id}/confirm";

    private final HoldService holdService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<HoldResponse> createHold(
        @RequestBody @Valid CreateHoldRequest request,
        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey
    ) {
        String requestHash = request.dropId() + ":" + request.userId() + ":" + request.quantity();
        IdempotencyReplay replay = idempotencyService.findReplay(idempotencyKey, CREATE_HOLD_ENDPOINT, requestHash).orElse(null);
        if (replay != null) {
            return ResponseEntity.status(replay.httpStatus()).body(readValue(replay.responseBody(), HoldResponse.class));
        }

        HoldResponse response = HoldResponse.from(
            holdService.createHold(request.dropId(), request.userId(), request.quantity(), idempotencyKey)
        );
        idempotencyService.storeResponse(idempotencyKey, HttpStatus.CREATED.value(), writeValue(response));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{holdId}/confirm")
    public ReservationResponse confirmHold(
        @PathVariable String holdId,
        @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey
    ) {
        String requestHash = holdId;
        IdempotencyReplay replay = idempotencyService.findReplay(idempotencyKey, CONFIRM_HOLD_ENDPOINT, requestHash).orElse(null);
        if (replay != null) {
            return readValue(replay.responseBody(), ReservationResponse.class);
        }

        ReservationResponse response = ReservationResponse.from(holdService.confirmHold(holdId, idempotencyKey));
        idempotencyService.storeResponse(idempotencyKey, HttpStatus.OK.value(), writeValue(response));
        return response;
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> cancelHold(@PathVariable String holdId) {
        holdService.cancelHold(holdId);
        return ResponseEntity.noContent().build();
    }

    private String writeValue(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ConflictException("Failed to serialize idempotent response");
        }
    }

    private <T> T readValue(String payload, Class<T> clazz) {
        try {
            return objectMapper.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            throw new ConflictException("Failed to deserialize idempotent response");
        }
    }
}
