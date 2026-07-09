package com.signalmap.query;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.h3.H3Service;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HeatmapService {

    private static final double MAX_SPAN_DEG = 1.0;
    private static final int MAX_CELLS = 20_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final H3Service h3;
    private final OperatorRegistry operators;

    public HeatmapService(NamedParameterJdbcTemplate jdbc, H3Service h3, OperatorRegistry operators) {
        this.jdbc = jdbc;
        this.h3 = h3;
        this.operators = operators;
    }

    public List<HeatmapCell> heatmap(double minLat, double minLng,
                                     double maxLat, double maxLng, String operator) {
        int operatorId = operators.idFor(operator).orElseThrow(() ->
                new IllegalArgumentException("Unknown operator '" + operator
                        + "'. Known: " + operators.knownNames()));

        if (maxLat - minLat > MAX_SPAN_DEG || maxLng - minLng > MAX_SPAN_DEG) {
            throw new IllegalArgumentException("Bounding box too large; zoom in.");
        }

        List<Long> candidates = h3.cellsInBbox(minLat, minLng, maxLat, maxLng);
        if (candidates.isEmpty()) return List.of();
        if (candidates.size() > MAX_CELLS) {
            throw new IllegalArgumentException("Too many cells in view; zoom in.");
        }

        var params = new MapSqlParameterSource()
                .addValue("cells", candidates)
                .addValue("op", operatorId);

        return jdbc.query("""
                SELECT h3_index, quality_score, sample_count, confidence
                FROM hex_cells
                WHERE h3_index IN (:cells) AND operator_id = :op
                """, params, (rs, rowNum) -> new HeatmapCell(
                Long.toHexString(rs.getLong("h3_index")),
                rs.getDouble("quality_score"),
                rs.getInt("sample_count"),
                rs.getDouble("confidence")));
    }
}