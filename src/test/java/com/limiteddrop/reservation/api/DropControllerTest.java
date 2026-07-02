package com.limiteddrop.reservation.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.limiteddrop.reservation.domain.Drop;
import com.limiteddrop.reservation.domain.DropStatus;
import com.limiteddrop.reservation.service.DropService;
import com.limiteddrop.reservation.service.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DropController.class)
class DropControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DropService dropService;

    private Drop sampleDrop() {
        Drop drop = new Drop();
        drop.setId(1L);
        drop.setName("Sneaker Drop");
        drop.setDescription("Limited edition");
        drop.setStatus(DropStatus.OPEN);
        drop.setOpensAt(Instant.parse("2024-01-01T00:00:00Z"));
        drop.setClosesAt(Instant.parse("2024-12-31T00:00:00Z"));
        drop.setUnitPrice(new BigDecimal("10.00"));
        drop.setCurrency("USD");
        drop.setTotalUnits(100);
        drop.setAvailableUnits(80);
        drop.setHeldUnits(15);
        drop.setConfirmedUnits(5);
        drop.setVersion(0L);
        return drop;
    }

    @Test
    void getDropsReturnsList() throws Exception {
        when(dropService.getOpenDrops()).thenReturn(List.of(sampleDrop()));

        mockMvc.perform(get("/drops"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].status").value("OPEN"))
            .andExpect(jsonPath("$[0].availableUnits").value(80));
    }

    @Test
    void getDropReturnsSingle() throws Exception {
        when(dropService.getDrop(1L)).thenReturn(sampleDrop());

        mockMvc.perform(get("/drops/{id}", 1L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.name").value("Sneaker Drop"));
    }

    @Test
    void getDropReturns404WhenMissing() throws Exception {
        when(dropService.getDrop(99L)).thenThrow(new NotFoundException("Drop not found"));

        mockMvc.perform(get("/drops/{id}", 99L))
            .andExpect(status().isNotFound());
    }
}
