#!/usr/bin/env python3
"""
Synthetic reading generator for SignalMap -- nationwide (India).

Readings are NOT scattered uniformly. Real crowdsourced data clusters where
people are, so we model that:

  * CITIES    -- dense clusters with a radial falloff from the centre
  * HIGHWAYS  -- sparse linear sampling along corridors between cities
  * ELSEWHERE -- genuinely empty (most of the map has no data, and that's correct)

Signal quality is deliberately sharp and uneven:
  * per-operator TOWERS create local peaks (a cell can be much better than its
    neighbours -- what a naive neighbour-average cannot predict)
  * DEAD ZONES have hard edges, not smooth tapers
  * urban cells have a higher base than rural ones
  * operators differ urban vs rural (Jio/Airtel urban-strong, BSNL relatively
    better rurally)

Usage:
    python generate_readings.py --region chennai --readings 40000
    python generate_readings.py --region south   --readings 150000
    python generate_readings.py --region india   --readings 300000
"""

import argparse
import json
import math
import random
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

OPERATORS = ["Airtel", "Jio", "Vi", "BSNL"]

# (name, lat, lng, weight ~ population, urban_radius_deg)
CITIES = [
    # South
    ("Chennai",            13.0827, 80.2707, 10.0, 0.18),
    ("Bengaluru",          12.9716, 77.5946, 10.0, 0.18),
    ("Hyderabad",          17.3850, 78.4867,  9.0, 0.17),
    ("Kochi",               9.9312, 76.2673,  4.0, 0.10),
    ("Coimbatore",         11.0168, 76.9558,  4.0, 0.10),
    ("Madurai",             9.9252, 78.1198,  3.0, 0.09),
    ("Visakhapatnam",      17.6868, 83.2185,  3.5, 0.10),
    ("Thiruvananthapuram",  8.5241, 76.9366,  3.0, 0.09),
    ("Mysuru",             12.2958, 76.6394,  2.5, 0.08),
    ("Tiruchirappalli",    10.7905, 78.7047,  2.5, 0.08),
    ("Mangaluru",          12.9141, 74.8560,  2.0, 0.08),
    ("Vijayawada",         16.5062, 80.6480,  2.5, 0.08),
    # West / Central
    ("Mumbai",             19.0760, 72.8777, 12.0, 0.20),
    ("Pune",               18.5204, 73.8567,  7.0, 0.15),
    ("Ahmedabad",          23.0225, 72.5714,  6.0, 0.14),
    ("Surat",              21.1702, 72.8311,  4.0, 0.11),
    ("Nagpur",             21.1458, 79.0882,  3.5, 0.10),
    ("Indore",             22.7196, 75.8577,  3.5, 0.10),
    # North
    ("Delhi",              28.7041, 77.1025, 12.0, 0.22),
    ("Jaipur",             26.9124, 75.7873,  5.0, 0.13),
    ("Lucknow",            26.8467, 80.9462,  4.5, 0.12),
    ("Kanpur",             26.4499, 80.3319,  3.0, 0.10),
    ("Chandigarh",         30.7333, 76.7794,  2.5, 0.09),
    ("Amritsar",           31.6340, 74.8723,  2.0, 0.08),
    # East
    ("Kolkata",            22.5726, 88.3639,  9.0, 0.18),
    ("Patna",              25.5941, 85.1376,  3.5, 0.10),
    ("Bhubaneswar",        20.2961, 85.8245,  2.5, 0.09),
    ("Guwahati",           26.1445, 91.7362,  2.0, 0.08),
    ("Ranchi",             23.3441, 85.3096,  2.0, 0.08),
]

# Highway corridors -- sparse readings sampled along these lines.
HIGHWAYS = [
    ("Chennai", "Bengaluru"), ("Bengaluru", "Hyderabad"), ("Chennai", "Madurai"),
    ("Bengaluru", "Kochi"), ("Coimbatore", "Kochi"), ("Chennai", "Coimbatore"),
    ("Hyderabad", "Nagpur"), ("Hyderabad", "Vijayawada"),
    ("Vijayawada", "Visakhapatnam"), ("Mumbai", "Pune"), ("Mumbai", "Surat"),
    ("Surat", "Ahmedabad"), ("Pune", "Bengaluru"), ("Nagpur", "Indore"),
    ("Delhi", "Jaipur"), ("Delhi", "Chandigarh"), ("Delhi", "Lucknow"),
    ("Lucknow", "Kanpur"), ("Kolkata", "Bhubaneswar"), ("Kolkata", "Patna"),
    ("Patna", "Ranchi"), ("Madurai", "Thiruvananthapuram"), ("Bengaluru", "Mysuru"),
]

REGIONS = {
    "india": None,
    "south": {"Chennai", "Bengaluru", "Hyderabad", "Kochi", "Coimbatore", "Madurai",
              "Visakhapatnam", "Thiruvananthapuram", "Mysuru", "Tiruchirappalli",
              "Mangaluru", "Vijayawada"},
    "chennai": {"Chennai"},
}

# --- signal physics -------------------------------------------------------

OPERATOR_BASE = {
    "Airtel": {"urban": 3.9, "rural": 2.6},
    "Jio":    {"urban": 4.1, "rural": 2.4},
    "Vi":     {"urban": 3.2, "rural": 1.6},
    "BSNL":   {"urban": 2.4, "rural": 2.1},
}

TOWERS_PER_CITY = 6       # per operator, per city
TOWER_BOOST = 1.7
TOWER_RADIUS = 0.012      # ~1.3 km: sharp, local

DEAD_ZONES_PER_CITY = 2
DEAD_RADIUS = (0.008, 0.020)
DEAD_STRENGTH = (2.0, 3.2)

NOISE_SD = 0.38

_rng = random.Random(20260712)  # fixed layout: the map is stable across runs

TOWERS = {op: [] for op in OPERATORS}
for _n, _la, _ln, _w, _rad in CITIES:
    for _op in OPERATORS:
        for _ in range(TOWERS_PER_CITY):
            TOWERS[_op].append((_la + _rng.gauss(0, _rad * 0.55),
                                _ln + _rng.gauss(0, _rad * 0.55)))

DEAD_ZONES = []
for _n, _la, _ln, _w, _rad in CITIES:
    for _ in range(DEAD_ZONES_PER_CITY):
        DEAD_ZONES.append((_la + _rng.gauss(0, _rad * 0.7),
                           _ln + _rng.gauss(0, _rad * 0.7),
                           _rng.uniform(*DEAD_RADIUS),
                           _rng.uniform(*DEAD_STRENGTH)))

CITY_BY_NAME = {c[0]: c for c in CITIES}


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def urbanness(lat, lng):
    """1.0 at a city centre, decaying to 0 in the countryside."""
    best = 0.0
    for _n, clat, clng, _w, rad in CITIES:
        d = math.hypot(lat - clat, lng - clng)
        if d < rad * 2.5:
            best = max(best, math.exp(-((d / rad) ** 2)))
    return best


def quality_at(lat, lng, operator):
    """True underlying signal quality at a point, 0..5. Deliberately sharp."""
    u = urbanness(lat, lng)
    base = OPERATOR_BASE[operator]
    q = base["rural"] + (base["urban"] - base["rural"]) * u

    nearest = min(math.hypot(lat - t[0], lng - t[1]) for t in TOWERS[operator])
    q += TOWER_BOOST * math.exp(-((nearest / TOWER_RADIUS) ** 2))

    for dz_lat, dz_lng, radius, strength in DEAD_ZONES:
        if math.hypot(lat - dz_lat, lng - dz_lng) < radius:
            q -= strength
            break

    q += random.gauss(0, NOISE_SD)
    return clamp(q, 0.0, 5.0)


def reading_from_quality(q):
    """Invert the backend scorer so rssi/kbps are consistent with quality q."""
    rssi = int(-105 + (q / 5.0) * 40 + random.gauss(0, 3))
    log_speed = 3.0 + (q / 5.0) * (math.log10(50_000) - 3.0) + random.gauss(0, 0.15)
    kbps = int(10 ** log_speed)
    latency = int(clamp(200 - q * 30 + random.gauss(0, 10), 8, 400))
    return rssi, max(kbps, 50), latency


# --- spatial sampling: where readings actually come from -------------------

def sample_city_point(cities, weights):
    """A point inside a city, denser toward the centre."""
    _n, lat, lng, _w, rad = random.choices(cities, weights=weights, k=1)[0]
    r = rad * (random.random() ** 0.65)   # radial falloff
    theta = random.uniform(0, 2 * math.pi)
    return lat + r * math.cos(theta), lng + r * math.sin(theta)


def sample_highway_point(routes):
    """A point along a corridor, with lateral scatter."""
    a, b = random.choice(routes)
    t = random.random()
    lat = a[1] + (b[1] - a[1]) * t
    lng = a[2] + (b[2] - a[2]) * t
    return lat + random.gauss(0, 0.02), lng + random.gauss(0, 0.02)


def make_reading(cities, weights, routes, highway_frac):
    if routes and random.random() < highway_frac:
        lat, lng = sample_highway_point(routes)
    else:
        lat, lng = sample_city_point(cities, weights)

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
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--readings", type=int, default=60000)
    ap.add_argument("--batch-size", type=int, default=1000)
    ap.add_argument("--devices", type=int, default=200)
    ap.add_argument("--region", choices=sorted(REGIONS), default="india")
    ap.add_argument("--highway-frac", type=float, default=0.12,
                    help="share of readings sampled along highway corridors")
    args = ap.parse_args()

    allowed = REGIONS[args.region]
    cities = [c for c in CITIES if allowed is None or c[0] in allowed]
    weights = [c[3] for c in cities]
    names = {c[0] for c in cities}
    routes = [(CITY_BY_NAME[a], CITY_BY_NAME[b])
              for a, b in HIGHWAYS if a in names and b in names]

    print(f"region={args.region}  cities={len(cities)}  corridors={len(routes)}")
    print(f"generating {args.readings} readings ({args.highway_frac:.0%} on highways)\n")

    total = 0
    remaining = args.readings
    while remaining > 0:
        n = min(args.batch_size, remaining)
        batch = [make_reading(cities, weights, routes, args.highway_frac)
                 for _ in range(n)]
        device_id = f"sim-device-{random.randint(1, args.devices):04d}"
        try:
            result = post_batch(args.url, device_id, batch)
            total += result.get("accepted", 0)
            print(f"  posted {n:5d}  accepted={result.get('accepted')} "
                  f"rejected={result.get('rejected')}")
        except urllib.error.URLError as e:
            print(f"  ERROR posting batch: {e}")
            print(f"  Is the API running at {args.url}?")
            return
        remaining -= n

    print(f"\nDone. {total} readings ingested.")
    print("Next:")
    print(f"  curl -X POST {args.url}/v1/admin/aggregate")
    print("  cd ml && python train.py")


if __name__ == "__main__":
    main()