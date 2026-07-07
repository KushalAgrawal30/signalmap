package com.signalmap.ingestion;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ReadingDto(

        @NotNull
        @DecimalMin("-90.0") @DecimalMax("90.0")
        Double lat,

        @NotNull
        @DecimalMin("-180.0") @DecimalMax("180.0")
        Double lng,

        @NotBlank
        String operator,

        String networkType,

        @NotNull
        Integer rssi,

        Integer latencyMs,

        @NotNull
        Integer downloadKbps,

        @NotNull
        Instant recordedAt
) {}