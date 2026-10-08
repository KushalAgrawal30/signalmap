# SignalMap

SignalMap turns scattered phone signal measurements into a searchable, predictive coverage map.

- A **Spring Boot backend** takes in readings in batches. It tags each reading with an [H3](https://h3geo.org/) hexagonal cell, then rolls the readings up into a quality score per cell. Recent readings are weighted more heavily than old ones.
- A **Python/FastAPI service** runs a LightGBM model that predicts signal quality for cells with no measurements, using the cells around them. On held-out data it beats a neighbour-average baseline by about 8% (lower mean absolute error).
- A **Next.js frontend** draws an interactive heatmap that adapts to the zoom level, with place search and side-by-side operator comparison.

Every answer is labelled with where it came from: `MEASURED`, `PREDICTED`, `FALLBACK`, or `NO_DATA`. The map never presents a guess as a measurement.

## Highlights

- **H3 hexagonal indexing with level-of-detail rendering.** Cells are merged into coarser parent hexes as you zoom out, so the map stays fast from street level up to the whole country.
- **Time-decayed aggregation.** Readings are weighted exponentially by age (τ = 7 days, 14-day window), balancing fresh data against stable scores. Each cell's confidence grows with its effective sample count.
- **A rigorously evaluated ML service.** The model is scored against a real baseline on held-out data, and its features are built so the target value can't leak in.
- **A hardened service boundary.** Calls to the ML service have a 400 ms timeout and a Resilience4j circuit breaker. If the service is down, coverage queries fall back to the neighbour average instead of failing.
- **Redis caching keyed by hex cell.** The cache is cleared when the scheduled aggregation job refreshes the data.

## Architecture

```
            ┌──────────────┐
            │  Next.js web │  heatmap · search · compare
            └──────┬───────┘
                   │ /v1/*
            ┌──────▼───────────────┐   timeout + circuit breaker   ┌──────────────────┐
 readings ─▶│ Spring Boot API :8080│──────────────────────────────▶│ FastAPI + LightGBM│
            │ ingest · aggregate · │◀──── prediction / fallback ───│       :8000       │
            │ query                │                               └────────┬─────────┘
            └───┬─────────────┬────┘                                        │ trains on
                │             │                                             │
         ┌──────▼─────┐  ┌────▼────┐                                        │
         │ PostgreSQL │  │  Redis  │                                        │
         │ + PostGIS  │◀─┼─────────┼────────────────────────────────────────┘
         └────────────┘  └─────────┘
```

| Directory    | What it is                                                                  |
|--------------|-----------------------------------------------------------------------------|
| `signalmap/` | Spring Boot API: ingestion, aggregation, coverage and heatmap queries, Flyway migrations, `docker-compose.yml` |
| `ml/`        | Feature engineering (`features.py`), training (`train.py`), serving (`serve.py`), and the trained model |
| `web/`       | Next.js and TypeScript frontend                                             |
| `tools/`     | `generate_readings.py`, a generator for realistic synthetic data across India |

**Stack:** Java 21 / Spring Boot, PostgreSQL + PostGIS, Redis, Python / FastAPI / LightGBM, H3, Next.js / TypeScript, Docker, Resilience4j.

## Getting started

**Prerequisites:** JDK 21, Maven 3.9+, Docker, Python 3.11+, and Node 18+.

```bash
# 1. Start Postgres + Redis
cd signalmap && docker compose up -d

# 2. Start the API (Flyway applies the schema on boot)
mvn spring-boot:run                       # http://localhost:8080

# 3. Start the ML service (in a new terminal)
cd ml && pip install -r requirements.txt
uvicorn serve:app --port 8000             # http://localhost:8000

# 4. Load synthetic data (in a new terminal)
python tools/generate_readings.py --region chennai --readings 40000
curl -X POST http://localhost:8080/v1/admin/aggregate    # roll up now instead of waiting for the 5-minute cron

# 5. Start the frontend
cd web && npm install && npm run dev      # http://localhost:3000
```

The ML service is optional. Without it, coverage queries still work and return `FALLBACK` results.

To retrain the model on your current data, run `python train.py` from `ml/`. It writes `models/model.joblib` and `models/metadata.json`. Then run `curl -X POST localhost:8000/reload` to load the new model without restarting.

The data generator supports three regions: `--region chennai | south | india`.

## API

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/v1/readings` | Ingest a batch: `{ deviceId, readings: [{ lat, lng, operator, networkType, rssi, latencyMs, downloadKbps, recordedAt }] }` |
| `GET`  | `/v1/coverage?lat=&lng=&operator=` | Quality score for one point, labelled with its source |
| `GET`  | `/v1/coverage/compare?lat=&lng=` | The same point compared across all operators |
| `GET`  | `/v1/heatmap?minLat=&minLng=&maxLat=&maxLng=&operator=&zoom=` | Hex cells in a bounding box, aggregated to fit the zoom level |
| `POST` | `/v1/admin/aggregate` | Trigger the aggregation job manually |
| `GET`  | `/v1/h3?lat=&lng=` | Debug helper: the H3 cell for a coordinate |

Example:

```bash
curl "http://localhost:8080/v1/coverage?lat=13.0827&lng=80.2707&operator=Jio"
```

## Configuration

Tuning lives in [signalmap/src/main/resources/application.yml](signalmap/src/main/resources/application.yml).

| Setting | Default | Meaning |
|---------|---------|---------|
| `signalmap.h3.resolution` | `8` | Hex size (about 0.7 km² per cell) |
| `signalmap.aggregation.tau-seconds` | `604800` | Time constant for the age decay (7 days) |
| `signalmap.aggregation.window-days` | `14` | Readings older than this are ignored |
| `signalmap.aggregation.confidence-k` | `10` | Effective samples needed for full confidence |
| `signalmap.aggregation.cron` | every 5 min | How often aggregation runs |
| `signalmap.coverage.min-samples` | `3` | Fewest samples for a cell to count as `MEASURED` |
| `ml.timeout-ms` | `400` | Timeout for calls to the ML service |
| `ml.enabled` | `true` | Set to `false` to skip predictions entirely |

## Model results

These numbers are from the most recent run, recorded in `ml/models/metadata.json`:

| Metric | Value |
|--------|-------|
| Model MAE | 0.214 |
| Neighbour-average baseline MAE | 0.233 |
| R² | 0.851 |
| Training rows | 23,741 |
