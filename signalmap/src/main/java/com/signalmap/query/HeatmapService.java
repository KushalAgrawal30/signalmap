package com.signalmap.query;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.h3.H3Service;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HeatmapService {

    private static final int MAX_CELLS = 15_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final H3Service h3;
    private final OperatorRegistry operators;

    public HeatmapService(NamedParameterJdbcTemplate jdbc, H3Service h3, OperatorRegistry operators) {
        this.jdbc = jdbc;
        this.h3 = h3;
        this.operators = operators;
    }

    public List<HeatmapCell> heatmap(double minLat, double minLng,
                                     double maxLat, double maxLng,
                                     String operator, int zoom) {
        int operatorId = operators.idFor(operator).orElseThrow(() ->
                new IllegalArgumentException("Unknown operator '" + operator
                        + "'. Known: " + operators.knownNames()));

        int displayRes = h3.resolutionForZoom(zoom);

        var params = new MapSqlParameterSource()
                .addValue("op", operatorId)
                .addValue("minLat", minLat).addValue("maxLat", maxLat)
                .addValue("minLng", minLng).addValue("maxLng", maxLng);

        // Postgres filters by viewport using the indexed centre columns; we then
        // roll each stored cell up to its parent at the display resolution.
        Map<Long, Agg> byParent = new HashMap<>();

        jdbc.query("""
                SELECT h3_index, quality_score, sample_count, confidence
                FROM hex_cells
                WHERE operator_id = :op
                  AND center_lat BETWEEN :minLat AND :maxLat
                  AND center_lng BETWEEN :minLng AND :maxLng
                """, params, (RowCallbackHandler) rs -> {
            long cell = rs.getLong("h3_index");
            long parent = h3.parentOf(cell, displayRes);
            double q = rs.getDouble("quality_score");
            int n = rs.getInt("sample_count");
            double c = rs.getDouble("confidence");

            byParent.compute(parent, (k, a) -> {
                if (a == null) a = new Agg();
                a.weightedQuality += q * n;
                a.weightedConfidence += c * n;
                a.samples += n;
                return a;
            });
        });

        List<HeatmapCell> out = new ArrayList<>(byParent.size());
        for (var e : byParent.entrySet()) {
            Agg a = e.getValue();
            if (a.samples == 0) continue;
            out.add(new HeatmapCell(
                    Long.toHexString(e.getKey()),
                    round(a.weightedQuality / a.samples),
                    a.samples,
                    round(a.weightedConfidence / a.samples)));
            if (out.size() >= MAX_CELLS) break;
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static final class Agg {
        double weightedQuality;
        double weightedConfidence;
        int samples;
    }
}