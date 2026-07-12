-- Heatmap queries need to filter cells by map viewport (a lat/lng box).
-- hex_cells only stored the H3 index, so a viewport query would have to scan
-- every row and decode each cell's centre in Java. Storing the centre lets
-- Postgres do the filtering with a plain index.

ALTER TABLE hex_cells
    ADD COLUMN center_lat DOUBLE PRECISION,
    ADD COLUMN center_lng DOUBLE PRECISION;

CREATE INDEX idx_hex_cells_center ON hex_cells (operator_id, center_lat, center_lng);