package com.signalmap.query;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.config.AppProperties;
import com.signalmap.h3.H3Service;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CoverageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final H3Service h3;
    private final OperatorRegistry operators;
    private final int minSamples;

    public CoverageService(NamedParameterJdbcTemplate jdbc, H3Service h3,
                           OperatorRegistry operators, AppProperties props) {
        this.jdbc = jdbc;
        this.h3 = h3;
        this.operators = operators;
        this.minSamples = props.coverage().minSamples();
    }

    public CoverageResponse coverage(double lat, double lng, String operator) {
        int operatorId = resolveOperator(operator);
        long cell = h3.cellOf(lat, lng);
        double[] center = h3.centerOf(cell);

        Cell measured = fetchCell(cell, operatorId);
        if (measured != null && measured.sampleCount() >= minSamples) {
            return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                    operator, round(measured.quality()), round(measured.confidence()),
                    measured.sampleCount(), CoverageResponse.Source.MEASURED);
        }
        return neighbourFallback(cell, center, operatorId, operator);
    }

    public List<CoverageResponse> compare(double lat, double lng) {
        long cell = h3.cellOf(lat, lng);
        double[] center = h3.centerOf(cell);

        var params = new MapSqlParameterSource().addValue("cell", cell);
        List<CoverageResponse> out = new ArrayList<>();
        jdbc.query("""
                SELECT operator_id, quality_score, sample_count, confidence
                FROM hex_cells WHERE h3_index = :cell
                """, params, rs -> {
            int opId = rs.getInt("operator_id");
            out.add(new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                    operators.nameFor(opId), round(rs.getDouble("quality_score")),
                    round(rs.getDouble("confidence")), rs.getInt("sample_count"),
                    CoverageResponse.Source.MEASURED));
        });
        out.sort((a, b) -> Double.compare(b.qualityScore(), a.qualityScore()));
        return out;
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

    private int resolveOperator(String operator) {
        return operators.idFor(operator).orElseThrow(() ->
                new IllegalArgumentException("Unknown operator '" + operator
                        + "'. Known: " + operators.knownNames()));
    }

    private static Double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record Cell(double quality, int sampleCount, double confidence) {}
}