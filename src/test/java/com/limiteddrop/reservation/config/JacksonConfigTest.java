package com.limiteddrop.reservation.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JacksonConfigTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

    @Test
    void objectMapperRoundTripsInstant() throws Exception {
        Instant original = Instant.parse("2024-01-01T00:00:00Z");

        String json = objectMapper.writeValueAsString(new Sample(original));
        Sample roundTripped = objectMapper.readValue(json, Sample.class);

        assertThat(roundTripped.at()).isEqualTo(original);
    }

    @Test
    void objectMapperDeserializesIsoInstant() throws Exception {
        Sample sample = objectMapper.readValue("{\"at\":\"2024-01-01T00:00:00Z\"}", Sample.class);

        assertThat(sample.at()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    record Sample(Instant at) {
    }
}
