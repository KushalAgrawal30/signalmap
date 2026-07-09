package com.signalmap.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/coverage")
public class CoverageController {

    private final CoverageService service;

    public CoverageController(CoverageService service) {
        this.service = service;
    }

    @GetMapping
    public CoverageResponse coverage(@RequestParam double lat,
                                     @RequestParam double lng,
                                     @RequestParam String operator) {
        return service.coverage(lat, lng, operator);
    }

    @GetMapping("/compare")
    public List<CoverageResponse> compare(@RequestParam double lat,
                                          @RequestParam double lng) {
        return service.compare(lat, lng);
    }
}