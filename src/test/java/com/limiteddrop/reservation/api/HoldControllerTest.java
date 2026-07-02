package com.limiteddrop.reservation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.limiteddrop.reservation.domain.Hold;
import com.limiteddrop.reservation.domain.Reservation;
import com.limiteddrop.reservation.domain.ReservationStatus;
import com.limiteddrop.reservation.service.HoldService;
import com.limiteddrop.reservation.service.IdempotencyReplay;
import com.limiteddrop.reservation.service.IdempotencyService;
import com.limiteddrop.reservation.service.exception.ConflictException;
import com.limiteddrop.reservation.service.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HoldController.class)
class HoldControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HoldService holdService;

    @MockBean
    private IdempotencyService idempotencyService;

    @Test
    void createHoldReturns201WithBody() throws Exception {
        when(idempotencyService.findReplay(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        Hold hold = Hold.create(1L, "u1", 2, Instant.now().plusSeconds(120), "key-1");
        when(holdService.createHold(eq(1L), eq("u1"), eq(2), eq("key-1"))).thenReturn(hold);

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":1,\"userId\":\"u1\",\"quantity\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(hold.getId()))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.quantity").value(2));
    }

    @Test
    void createHoldRejectsInvalidQuantityWith422() throws Exception {
        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":1,\"userId\":\"u1\",\"quantity\":0}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createHoldMissingIdempotencyHeaderReturns400() throws Exception {
        mockMvc.perform(post("/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":1,\"userId\":\"u1\",\"quantity\":2}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createHoldMalformedBodyReturns400() throws Exception {
        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createHoldReturns409WhenSoldOut() throws Exception {
        when(idempotencyService.findReplay(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(holdService.createHold(any(), anyString(), anyInt(), anyString()))
            .thenThrow(new ConflictException("Not enough inventory available"));

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":1,\"userId\":\"u1\",\"quantity\":2}"))
            .andExpect(status().isConflict());
    }

    @Test
    void createHoldReturns404WhenDropMissing() throws Exception {
        when(idempotencyService.findReplay(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(holdService.createHold(any(), anyString(), anyInt(), anyString()))
            .thenThrow(new NotFoundException("Drop not found"));

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":99,\"userId\":\"u1\",\"quantity\":2}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void cancelHoldReturns204() throws Exception {
        mockMvc.perform(delete("/holds/{id}", "hold-1"))
            .andExpect(status().isNoContent());

        verify(holdService).cancelHold("hold-1");
    }

    @Test
    void createHoldReplayReturnsStoredHoldWithoutInvokingService() throws Exception {
        String storedBody = "{\"id\":\"hold-7\",\"dropId\":1,\"userId\":\"u1\",\"quantity\":2,"
            + "\"status\":\"ACTIVE\",\"expiresAt\":\"2024-01-01T00:00:00Z\"}";
        when(idempotencyService.findReplay(anyString(), anyString(), anyString()))
            .thenReturn(Optional.of(new IdempotencyReplay(201, storedBody)));

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":1,\"userId\":\"u1\",\"quantity\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("hold-7"));

        verify(holdService, never()).createHold(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void createHoldGenericErrorReturns500WithoutLeakingDetails() throws Exception {
        when(idempotencyService.findReplay(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(holdService.createHold(any(), anyString(), anyInt(), anyString()))
            .thenThrow(new RuntimeException("sensitive internal failure detail"));

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dropId\":1,\"userId\":\"u1\",\"quantity\":2}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void confirmHoldHappyPathReturnsReservation() throws Exception {
        when(idempotencyService.findReplay(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        Reservation reservation = new Reservation();
        reservation.setId(99L);
        reservation.setHoldId("hold-1");
        reservation.setDropId(1L);
        reservation.setUserId("u1");
        reservation.setQuantity(2);
        reservation.setTotalAmount(new BigDecimal("20.00"));
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setConfirmedAt(Instant.parse("2024-01-01T00:00:00Z"));
        when(holdService.confirmHold(eq("hold-1"), eq("key-1"))).thenReturn(reservation);

        mockMvc.perform(post("/holds/{id}/confirm", "hold-1")
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(99))
            .andExpect(jsonPath("$.holdId").value("hold-1"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmHoldReplayReturnsStoredBodyWithoutInvokingService() throws Exception {
        String storedBody = "{\"id\":10,\"holdId\":\"hold-1\",\"dropId\":1,\"userId\":\"u1\","
            + "\"quantity\":2,\"totalAmount\":20.00,\"status\":\"CONFIRMED\","
            + "\"confirmedAt\":\"2024-01-01T00:00:00Z\"}";
        when(idempotencyService.findReplay(anyString(), anyString(), anyString()))
            .thenReturn(Optional.of(new IdempotencyReplay(200, storedBody)));

        mockMvc.perform(post("/holds/{id}/confirm", "hold-1")
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(10))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(holdService, never()).confirmHold(anyString(), anyString());
    }
}
