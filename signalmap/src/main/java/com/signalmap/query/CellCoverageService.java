package com.signalmap.query;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.config.AppProperties;
import com.signalmap.h3.H3Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CellCoverageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final H3Service h3;
    private final OperatorRegistry operators;
    private final int minSamples;

    public CellCoverageService(NamedParameterJdbcTemplate jdbc, H3Service h3,
                               OperatorRegistry operators, AppProperties props) {
        this.jdbc = jdbc;
        this.h3 = h3;
        this.operators = operators;
        this.minSamples = props.coverage().minSamples();
    }

    @Cacheable(cacheNames = "coverage", key = "#cell + ':' + #operator")
    public CoverageResponse forCell(long cell, String operator) {
        int operatorId = operators.idFor(operator).orElseThrow(() ->
                new IllegalArgumentException("Unknown operator '" + operator
                        + "'. Known: " + operators.knownNames()));
        double[] center = h3.centerOf(cell);

        Cell measured = fetchCell(cell, operatorId);
        if (measured != null && measured.sampleCount() >= minSamples) {
            return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                    operator, round(measured.quality()), round(measured.confidence()),
                    measured.sampleCount(), CoverageResponse.Source.MEASURED);
        }
        return neighbourFallback(cell, center, operatorId, operator);
    }

    private CoverageResponse neighbourFallback(long cell, double[] center,
                                               int operatorId, String operator) {
        List<Long> neighbours = h3.neighbours(cell, 1);
        var params = new MapSqlParameterSource()
                .addValue("cells", neighbours)
                .addValue("op", operatorId);

        double[] acc = new double[2]; // [weightedQualitySum, sampleSum]
        int[] samples = new int[1];
        jdbc.query("""
                SELECT quality_score, sample_count
                FROM hex_cells
                WHERE h3_index IN (:cells) AND operator_id = :op
                """, params, rs -> {
            int n = rs.getInt("sample_count");
            acc[0] += rs.getDouble("quality_score") * n;
            acc[1] += n;
            samples[0] += n;
        });

        if (acc[1] == 0) {
            return CoverageResponse.noData(cell, center, operator);
        }
        double avgQuality = acc[0] / acc[1];
        double confidence = Math.min(0.5, acc[1] / 20.0);
        return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                operator, round(avgQuality), round(confidence), samples[0],
                CoverageResponse.Source.FALLBACK);
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
}