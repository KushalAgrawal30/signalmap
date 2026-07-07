package com.signalmap.ingestion;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/readings")
public class IngestionController {

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IngestResult> submit(@Valid @RequestBody ReadingBatchRequest batch) {
        IngestResult result = service.ingest(batch);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}