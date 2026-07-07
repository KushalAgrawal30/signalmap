package com.signalmap.h3;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Step-1 sanity endpoint: confirms the H3 wiring works end to end.
 *   GET /v1/h3?lat=13.0827&lng=80.2707
 * Returns the cell id (as an unsigned string) and its center coordinate.
 * This gets removed / folded into real endpoints in later steps.
 */
@RestController
public class H3DevController {

    private final H3Service h3;

    public H3DevController(H3Service h3) {
        this.h3 = h3;
    }

    @GetMapping("/v1/h3")
    public Map<String, Object> cell(@RequestParam double lat, @RequestParam double lng) {
        long cell = h3.cellOf(lat, lng);
        double[] center = h3.centerOf(cell);
        return Map.of(
                "h3Index", Long.toUnsignedString(cell),
                "resolution", h3.resolution(),
                "inputLat", lat,
                "inputLng", lng,
                "centerLat", center[0],
                "centerLng", center[1]
        );
    }
}
