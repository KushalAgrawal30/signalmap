package com.signalmap.aggregation;

import com.signalmap.config.AppProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AggregationService {

    private static final String ROLLUP_SQL = """
            INSERT INTO hex_cells
                (h3_index, resolution, operator_id, quality_score, sample_count, confidence, last_updated)
            SELECT
                w.h3_index,
                :resolution,
                w.operator_id,
                SUM(w.weight * w.quality) / NULLIF(SUM(w.weight), 0)  AS quality_score,
                COUNT(*)                                             AS sample_count,
                LEAST(1.0, SUM(w.weight) / :confidenceK)             AS confidence,
                now()                                               AS last_updated
            FROM (
                SELECT
                    h3_index,
                    operator_id,
                    quality,
                    exp( - EXTRACT(EPOCH FROM (now() - recorded_at)) / :tauSeconds ) AS weight
                FROM readings
                WHERE recorded_at > now() - make_interval(days => :windowDays)
            ) w
            GROUP BY w.h3_index, w.operator_id
            ON CONFLICT (h3_index, operator_id) DO UPDATE SET
                quality_score = EXCLUDED.quality_score,
                sample_count  = EXCLUDED.sample_count,
                confidence    = EXCLUDED.confidence,
                last_updated  = EXCLUDED.last_updated
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppProperties.Aggregation cfg;
    private final int resolution;

    public AggregationService(NamedParameterJdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.cfg = props.aggregation();
        this.resolution = props.h3().resolution();
    }

    @Transactional
    public int run() {
        var params = new MapSqlParameterSource()
                .addValue("resolution", resolution)
                .addValue("confidenceK", cfg.confidenceK())
                .addValue("tauSeconds", cfg.tauSeconds())
                .addValue("windowDays", cfg.windowDays());
        return jdbc.update(ROLLUP_SQL, params);
    }
}