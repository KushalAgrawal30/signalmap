package com.signalmap.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/heatmap")
public class HeatmapController {

    private final HeatmapService service;

    public HeatmapController(HeatmapService service) {
        this.service = service;
    }

    /** GET /v1/heatmap?minLat=..&minLng=..&maxLat=..&maxLng=..&operator=Jio&zoom=12 */
    @GetMapping
    public List<HeatmapCell> heatmap(@RequestParam double minLat,
                                     @RequestParam double minLng,
                                     @RequestParam double maxLat,
                                     @RequestParam double maxLng,
                                     @RequestParam String operator,
                                     @RequestParam(defaultValue = "12") int zoom) {
        return service.heatmap(minLat, minLng, maxLat, maxLng, operator, zoom);
    }
}