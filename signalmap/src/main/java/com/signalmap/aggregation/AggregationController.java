package com.signalmap.aggregation;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/v1/admin")
public class AggregationController {

    private final AggregationService service;

    public AggregationController(AggregationService service) {
        this.service = service;
    }

    @PostMapping("/aggregate")
    public Map<String, Object> aggregate() {
        int cells = service.run();
        return Map.of("status", "ok", "cellsWritten", cells);
    }
}