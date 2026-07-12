package com.signalmap.query;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.config.AppProperties;
import com.signalmap.h3.H3Service;
import com.signalmap.ml.MlClient;
import com.signalmap.ml.MlDtos;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolution order for a query:
 *   1. MEASURED  - the cell itself has enough readings
 *   2. PREDICTED - the ML service estimates it from neighbouring cells
 *   3. FALLBACK  - ML unavailable: sample-weighted neighbour average
 *   4. NO_DATA   - nothing nearby at all
 *
 * Steps 2 and 3 are the important pair: the ML service is an ENHANCEMENT, not a
 * dependency. If it's slow, down, or the circuit is open, we still answer.
 */
@Service
public class CellCoverageService {

    private static final int NEIGHBOUR_RINGS = 2; // ML features use ring-1 + ring-2

    private final NamedParameterJdbcTemplate jdbc;
    private final H3Service h3;
    private final OperatorRegistry operators;
    private final MlClient ml;
    private final int minSamples;

    public CellCoverageService(NamedParameterJdbcTemplate jdbc, H3Service h3,
                               OperatorRegistry operators, MlClient ml, AppProperties props) {
        this.jdbc = jdbc;
        this.h3 = h3;
        this.operators = operators;
        this.ml = ml;
        this.minSamples = props.coverage().minSamples();
    }

    @Cacheable(cacheNames = "coverage", key = "#cell + ':' + #operator")
    public CoverageResponse forCell(long cell, String operator) {
        int operatorId = operators.idFor(operator).orElseThrow(() ->
                new IllegalArgumentException("Unknown operator '" + operator
                        + "'. Known: " + operators.knownNames()));
        double[] center = h3.centerOf(cell);

        // 1. Measured?
        Cell measured = fetchCell(cell, operatorId);
        if (measured != null && measured.sampleCount() >= minSamples) {
            return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                    operator, round(measured.quality()), round(measured.confidence()),
                    measured.sampleCount(), CoverageResponse.Source.MEASURED);
        }

        // Gather the neighbourhood once -- used by BOTH the ML call and the fallback.
        List<Neighbour> neighbours = fetchNeighbours(cell, operatorId);
        if (neighbours.isEmpty()) {
            return CoverageResponse.noData(cell, center, operator);
        }
        int totalSamples = neighbours.stream().mapToInt(Neighbour::samples).sum();

        // 2. Ask the ML service.
        var mlNeighbours = neighbours.stream()
                .map(n -> new MlDtos.Neighbour(Long.toHexString(n.cell()), n.quality(), n.samples()))
                .toList();

        var predicted = ml.predict(Long.toHexString(cell), operatorId, mlNeighbours);
        if (predicted.isPresent() && predicted.get().predictedScore() != null) {
            var p = predicted.get();
            return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                    operator, round(p.predictedScore()), round(p.confidence()),
                    totalSamples, CoverageResponse.Source.PREDICTED);
        }

        // 3. ML unavailable -> neighbour average, clearly labelled FALLBACK.
        double weighted = 0, weight = 0;
        for (Neighbour n : neighbours) {
            weighted += n.quality() * n.samples();
            weight += n.samples();
        }
        double avg = weighted / weight;
        double confidence = Math.min(0.5, weight / 20.0);
        return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                operator, round(avg), round(confidence), totalSamples,
                CoverageResponse.Source.FALLBACK);
    }

    private List<Neighbour> fetchNeighbours(long cell, int operatorId) {
        List<Long> ring = h3.neighbours(cell, NEIGHBOUR_RINGS);
        if (ring.isEmpty()) return List.of();

        var params = new MapSqlParameterSource()
                .addValue("cells", ring)
                .addValue("op", operatorId);

        List<Neighbour> out = new ArrayList<>();
        jdbc.query("""
                SELECT h3_index, quality_score, sample_count
                FROM hex_cells
                WHERE h3_index IN (:cells) AND operator_id = :op
                """, params, (RowCallbackHandler) rs -> out.add(new Neighbour(
                rs.getLong("h3_index"), rs.getDouble("quality_score"), rs.getInt("sample_count"))));
        return out;
    }

    private Cell fetchCell(long cell, int operatorId) {
        var params = new MapSqlParameterSource()
                .addValue("cell", cell)
                .addValue("op", operatorId);
        List<Cell> rows = jdbc.query("""
                SELECT quality_score, sample_count, confidence
                FROM hex_cells WHERE h3_index = :cell AND operator_id = :op
                """, params, (rs, i) -> new Cell(
                rs.getDouble("quality_score"), rs.getInt("sample_count"), rs.getDouble("confidence")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Cell(double quality, int sampleCount, double confidence) {}

    private record Neighbour(long cell, double quality, int samples) {}
}