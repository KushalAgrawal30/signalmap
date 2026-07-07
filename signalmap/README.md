# SignalMap — backend core

Crowdsourced mobile network quality mapping. We're building it **step by step**.

## Step 1 (this commit): foundation

A Spring Boot app that boots, connects to Postgres, applies the schema via
Flyway, and can turn a coordinate into an H3 hexagonal cell.

Nothing writes or reads data yet — this step just proves the skeleton and the
H3 wiring work.

### Prerequisites
- JDK 21, Maven 3.9+
- Docker (for Postgres)

### Run it
```bash
# 1. Start Postgres
docker compose up -d

# 2. Start the API (Flyway applies V1__init.sql on boot)
mvn spring-boot:run
```

### Verify
```bash
# App + DB healthy?
curl http://localhost:8080/actuator/health        # -> {"status":"UP"}

# H3 wiring works? (Chennai city center)
curl "http://localhost:8080/v1/h3?lat=13.0827&lng=80.2707"
# -> { "h3Index": "...", "resolution": 8, "centerLat": ..., "centerLng": ... }
```

Two nearby coordinates should return the **same** `h3Index`; far-apart ones
should differ. That's the whole point of the hex grid.

### What's in the schema
`operators` (seeded with Airtel/Jio/Vi/BSNL), `readings` (raw, source of truth),
`hex_cells` (aggregated serving table). They're empty for now.

---

### Next: Step 2 — Ingestion
`POST /v1/readings` to accept batches of measurements, validate them, compute
the H3 cell + a quality score, and batch-insert into `readings`.
