#!/usr/bin/env python3
"""
Synthetic reading generator for SignalMap.
Fabricates plausible signal readings around Chennai and POSTs them to the
ingestion API in batches, with per-operator coverage variation and engineered
dead zones. No third-party deps (urllib only).

Usage:
    python generate_readings.py
    python generate_readings.py --readings 10000
    python generate_readings.py --url http://localhost:8080
"""

import argparse
import json
import math
import random
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone

CENTER_LAT, CENTER_LNG = 13.0827, 80.2707
SPREAD_DEG = 0.12  # ~13 km box around the center

OPERATORS = ["Airtel", "Jio", "Vi", "BSNL"]

# Each operator gets a base quality (0..5), a broad coverage "focus" point,
# and a set of TOWERS. Towers create sharp local peaks -- this is what makes the
# world hard enough that a flat neighbour-average can't predict it, and it's also
# how real coverage actually works (signal is tower-driven, not smoothly varying).
OPERATOR_PROFILE = {
    "Airtel": {"base": 3.4, "focus": (13.06, 80.25)},
    "Jio":    {"base": 3.6, "focus": (13.10, 80.28)},
    "Vi":     {"base": 2.8, "focus": (13.08, 80.24)},
    "BSNL":   {"base": 2.1, "focus": (13.05, 80.30)},
}

TOWERS_PER_OPERATOR = 7
TOWER_BOOST = 1.8       # how much a tower lifts nearby cells
TOWER_RADIUS = 0.012    # ~1.3 km -- sharp, local

# Dead zones now have a HARD edge (a cliff, not a taper), so cells just inside
# differ sharply from their neighbours just outside.
DEAD_ZONES = [
    (13.11, 80.23, 0.018, 3.0),
    (13.04, 80.29, 0.014, 2.6),
    (13.09, 80.30, 0.010, 2.4),
]

# Fixed tower layout (seeded) so the map is stable across runs.
_tower_rng = random.Random(20260712)
TOWERS = {
    op: [(CENTER_LAT + _tower_rng.uniform(-SPREAD_DEG, SPREAD_DEG),
          CENTER_LNG + _tower_rng.uniform(-SPREAD_DEG, SPREAD_DEG))
         for _ in range(TOWERS_PER_OPERATOR)]
    for op in OPERATORS
}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def quality_at(lat, lng, operator):
    """The 'true' underlying signal quality at a point for an operator."""
    prof = OPERATOR_PROFILE[operator]
    q = prof["base"]

    # Broad degradation with distance from the operator's focus area.
    fl, fn = prof["focus"]
    q -= math.hypot(lat - fl, lng - fn) * 8.0

    # Tower boost: a sharp Gaussian peak around the nearest tower.
    nearest = min(math.hypot(lat - t[0], lng - t[1]) for t in TOWERS[operator])
    q += TOWER_BOOST * math.exp(-((nearest / TOWER_RADIUS) ** 2))

    # Dead zones: a hard cliff, not a smooth taper.
    for dz_lat, dz_lng, radius, strength in DEAD_ZONES:
        if math.hypot(lat - dz_lat, lng - dz_lng) < radius:
            q -= strength

    q += random.gauss(0, 0.35)  # measurement noise (real readings are noisy)
    return clamp(q, 0.0, 5.0)

def reading_from_quality(q):
    rssi = int(-105 + (q / 5.0) * 40 + random.gauss(0, 3))
    log_speed = 3.0 + (q / 5.0) * (math.log10(50_000) - 3.0) + random.gauss(0, 0.15)
    kbps = int(10 ** log_speed)
    latency = int(clamp(200 - q * 30 + random.gauss(0, 10), 8, 400))
    return rssi, max(kbps, 50), latency


def make_reading():
    lat = CENTER_LAT + random.uniform(-SPREAD_DEG, SPREAD_DEG)
    lng = CENTER_LNG + random.uniform(-SPREAD_DEG, SPREAD_DEG)
    operator = random.choice(OPERATORS)
    q = quality_at(lat, lng, operator)
    rssi, kbps, latency = reading_from_quality(q)
    recorded = datetime.now(timezone.utc) - timedelta(
        seconds=random.uniform(0, 5 * 24 * 3600))
    return {
        "lat": round(lat, 6),
        "lng": round(lng, 6),
        "operator": operator,
        "networkType": random.choice(["4G", "5G"]),
        "rssi": rssi,
        "latencyMs": latency,
        "downloadKbps": kbps,
        "recordedAt": recorded.isoformat().replace("+00:00", "Z"),
    }


def post_batch(url, device_id, readings):
    payload = json.dumps({"deviceId": device_id, "readings": readings}).encode()
    req = urllib.request.Request(
        url + "/v1/readings", data=payload,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--readings", type=int, default=6000)
    ap.add_argument("--batch-size", type=int, default=500)
    ap.add_argument("--devices", type=int, default=40)
    args = ap.parse_args()

    total_accepted = 0
    remaining = args.readings
    while remaining > 0:
        n = min(args.batch_size, remaining)
        batch = [make_reading() for _ in range(n)]
        device_id = f"sim-device-{random.randint(1, args.devices):03d}"
        try:
            result = post_batch(args.url, device_id, batch)
            total_accepted += result.get("accepted", 0)
            print(f"  posted {n:4d}  accepted={result.get('accepted')} "
                  f"rejected={result.get('rejected')}")
        except urllib.error.URLError as e:
            print(f"  ERROR posting batch: {e}")
            print("  Is the API running at", args.url, "?")
            return
        remaining -= n

    print(f"\nDone. {total_accepted} readings ingested.")
    print("Now trigger aggregation:")
    print(f"  curl -X POST {args.url}/v1/admin/aggregate")


if __name__ == "__main__":
    main()