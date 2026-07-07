-- SignalMap initial schema.
-- H3 indexes are stored as BIGINT (H3 v4 returns a 64-bit cell id) -- far
-- better to index and join on than the string form.

CREATE EXTENSION IF NOT EXISTS postgis;

-- Lookup table so operator strings stay clean and joinable.
CREATE TABLE operators (
    id      SERIAL PRIMARY KEY,
    name    TEXT NOT NULL UNIQUE,
    country TEXT NOT NULL DEFAULT 'IN'
);

INSERT INTO operators (name, country) VALUES
    ('Airtel', 'IN'),
    ('Jio',    'IN'),
    ('Vi',     'IN'),
    ('BSNL',   'IN');

-- Raw, append-only readings. This is the source of truth: hex_cells can
-- always be recomputed from here if the scoring formula changes.
CREATE TABLE readings (
    id            BIGSERIAL PRIMARY KEY,
    device_id     TEXT,
    lat           DOUBLE PRECISION NOT NULL,
    lng           DOUBLE PRECISION NOT NULL,
    h3_index      BIGINT NOT NULL,
    operator_id   INTEGER NOT NULL REFERENCES operators(id),
    network_type  TEXT,
    rssi          INTEGER,          -- dBm, e.g. -85
    latency_ms    INTEGER,
    download_kbps INTEGER,
    quality       DOUBLE PRECISION NOT NULL,  -- derived 0..5 score (compute-on-write)
    recorded_at   TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_readings_cell_op_time
    ON readings (h3_index, operator_id, recorded_at DESC);

-- Aggregated, query-serving table. One row per (cell, operator).
CREATE TABLE hex_cells (
    h3_index      BIGINT NOT NULL,
    resolution    INTEGER NOT NULL,
    operator_id   INTEGER NOT NULL REFERENCES operators(id),
    quality_score DOUBLE PRECISION NOT NULL,
    sample_count  INTEGER NOT NULL,
    confidence    DOUBLE PRECISION NOT NULL,  -- 0..1
    last_updated  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (h3_index, operator_id)
);

CREATE INDEX idx_hex_cells_operator ON hex_cells (operator_id);
