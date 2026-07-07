package com.signalmap.ingestion;

import com.signalmap.common.OperatorRegistry;
import com.signalmap.h3.H3Service;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private static final String INSERT_SQL = """
            INSERT INTO readings
                (device_id, lat, lng, h3_index, operator_id, network_type,
                 rssi, latency_ms, download_kbps, quality, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final int MAX_SAMPLE_ERRORS = 10;

    private final JdbcTemplate jdbc;
    private final OperatorRegistry operators;
    private final H3Service h3;
    private final QualityScorer scorer;

    public IngestionService(JdbcTemplate jdbc, OperatorRegistry operators,
                            H3Service h3, QualityScorer scorer) {
        this.jdbc = jdbc;
        this.operators = operators;
        this.h3 = h3;
        this.scorer = scorer;
    }

    @Transactional
    public IngestResult ingest(ReadingBatchRequest batch) {
        String deviceId = batch.deviceId();
        List<Row> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int rejected = 0;

        for (int i = 0; i < batch.readings().size(); i++) {
            ReadingDto r = batch.readings().get(i);

            var opId = operators.idFor(r.operator());
            if (opId.isEmpty()) {
                rejected++;
                addError(errors, i, "unknown operator '" + r.operator()
                        + "' (known: " + operators.knownNames() + ")");
                continue;
            }
            if (r.recordedAt().isAfter(Instant.now().plusSeconds(300))) {
                rejected++;
                addError(errors, i, "recordedAt is in the future");
                continue;
            }

            long cell = h3.cellOf(r.lat(), r.lng());
            double quality = scorer.score(r.rssi(), r.downloadKbps());
            valid.add(new Row(deviceId, r, cell, opId.get(), quality));
        }

        if (!valid.isEmpty()) {
            batchInsert(valid);
        }
        return new IngestResult(valid.size(), rejected, errors);
    }

    private void batchInsert(List<Row> rows) {
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Row row = rows.get(i);
                ReadingDto r = row.dto();
                ps.setString(1, row.deviceId());
                ps.setDouble(2, r.lat());
                ps.setDouble(3, r.lng());
                ps.setLong(4, row.cell());
                ps.setInt(5, row.operatorId());
                ps.setString(6, r.networkType());
                ps.setInt(7, r.rssi());
                if (r.latencyMs() != null) ps.setInt(8, r.latencyMs());
                else ps.setNull(8, java.sql.Types.INTEGER);
                ps.setInt(9, r.downloadKbps());
                ps.setDouble(10, row.quality());
                ps.setTimestamp(11, Timestamp.from(r.recordedAt()));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    private static void addError(List<String> errors, int index, String msg) {
        if (errors.size() < MAX_SAMPLE_ERRORS) {
            errors.add("row " + index + ": " + msg);
        }
    }

    private record Row(String deviceId, ReadingDto dto, long cell, int operatorId, double quality) {}
}