package com.signalmap.query;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.h3.H3Service;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CoverageService {

    private final NamedParameterJdbcTemplate jdbc;
    private final H3Service h3;
    private final OperatorRegistry operators;
    private final CellCoverageService cellService;

    public CoverageService(NamedParameterJdbcTemplate jdbc, H3Service h3,
                           OperatorRegistry operators, CellCoverageService cellService) {
        this.jdbc = jdbc;
        this.h3 = h3;
        this.operators = operators;
        this.cellService = cellService;
    }

    public CoverageResponse coverage(double lat, double lng, String operator) {
        long cell = h3.cellOf(lat, lng);
        return cellService.forCell(cell, operator);
    }

    public List<CoverageResponse> compare(double lat, double lng) {
        long cell = h3.cellOf(lat, lng);
        double[] center = h3.centerOf(cell);

        var params = new MapSqlParameterSource().addValue("cell", cell);
        List<CoverageResponse> out = jdbc.query("""
                SELECT operator_id, quality_score, sample_count, confidence
                FROM hex_cells WHERE h3_index = :cell
                """, params, (rs, i) -> new CoverageResponse(
                Long.toUnsignedString(cell), center[0], center[1],
                operators.nameFor(rs.getInt("operator_id")),
                round(rs.getDouble("quality_score")), round(rs.getDouble("confidence")),
                rs.getInt("sample_count"), CoverageResponse.Source.MEASURED));

        out.sort((a, b) -> Double.compare(b.qualityScore(), a.qualityScore()));
        return out;
    }

    private static Double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}