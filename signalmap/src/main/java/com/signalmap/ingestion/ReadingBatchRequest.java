package com.signalmap.ingestion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReadingBatchRequest(

        String deviceId,

        @NotEmpty
        @Size(max = 2000)
        @Valid
        List<ReadingDto> readings
) {}