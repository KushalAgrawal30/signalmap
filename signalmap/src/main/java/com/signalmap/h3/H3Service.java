package com.signalmap.h3;

import com.signalmap.config.AppProperties;
import com.uber.h3core.H3Core;
import com.uber.h3core.util.LatLng;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin wrapper over the Uber H3 v4 API so the rest of the codebase never
 * touches raw H3 method names. Grows as later steps need neighbours, etc.
 */
@Service
public class H3Service {

    private final H3Core h3;
    private final int resolution;

    public H3Service(H3Core h3, AppProperties props) {
        this.h3 = h3;
        this.resolution = props.h3().resolution();
    }

    public int resolution() {
        return resolution;
    }

    /** Resolve a coordinate to its H3 cell id at the configured resolution. */
    public long cellOf(double lat, double lng) {
        return h3.latLngToCell(lat, lng, resolution);
    }

    /** Center coordinate of a cell, as [lat, lng]. */
    public double[] centerOf(long cell) {
        LatLng c = h3.cellToLatLng(cell);
        return new double[]{c.lat, c.lng};
    }

    /** Neighbouring cells within k rings, EXCLUDING the origin cell. */
    public List<Long> neighbours(long cell, int k) {
        return h3.gridDisk(cell, k).stream()
                .filter(c -> c != cell)
                .toList();
    }

    /** All cells at the configured resolution whose area intersects the bbox. */
    public List<Long> cellsInBbox(double minLat, double minLng, double maxLat, double maxLng) {
        List<LatLng> polygon = List.of(
                new LatLng(minLat, minLng),
                new LatLng(minLat, maxLng),
                new LatLng(maxLat, maxLng),
                new LatLng(maxLat, minLng)
        );
        return h3.polygonToCells(polygon, List.of(), resolution);
    }

    /** The ancestor of a cell at a coarser resolution. */
    public long parentOf(long cell, int targetResolution) {
        return h3.cellToParent(cell, targetResolution);
    }

    public int resolutionForZoom(int zoom) {
        int r;
        if (zoom <= 5)       r = 3;   // country
        else if (zoom <= 7)  r = 4;   // large region
        else if (zoom <= 9)  r = 5;   // metro area
        else if (zoom <= 10) r = 6;
        else if (zoom <= 12) r = 7;
        else                 r = 8;   // street level: the storage resolution
        return Math.min(r, resolution);
    }
}
