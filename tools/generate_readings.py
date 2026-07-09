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

OPERATOR_PROFILE = {
    "Airtel": {"base": 4.2, "focus": (13.06, 80.25)},
    "Jio":    {"base": 4.4, "focus": (13.10, 80.28)},
    "Vi":     {"base": 3.4, "focus": (13.08, 80.24)},
    "BSNL":   {"base": 2.6, "focus": (13.05, 80.30)},
}

DEAD_ZONES = [
    (13.11, 80.23, 0.020, 3.0),
    (13.04, 80.29, 0.015, 2.5),
]


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def quality_at(lat, lng, operator):
    prof = OPERATOR_PROFILE[operator]
    q = prof["base"]
    fl, fn = prof["focus"]
    dist = math.hypot(lat - fl, lng - fn)
    q -= dist * 12.0
    for dz_lat, dz_lng, radius, strength in DEAD_ZONES:
        d = math.hypot(lat - dz_lat, lng - dz_lng)
        if d < radius:
            q -= strength * (1 - d / radius)
    q += random.gauss(0, 0.3)
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