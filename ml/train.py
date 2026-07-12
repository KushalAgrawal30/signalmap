#!/usr/bin/env python3
"""
Train the SignalMap cell-quality prediction model.

Runs OFFLINE (never in the request path). Reads hex_cells from Postgres, builds
neighbourhood features for every well-measured cell, trains LightGBM to predict
a cell's quality from its surroundings alone, evaluates against the
neighbour-average baseline, and writes a versioned model artifact to models/.

Usage:
    python train.py
    python train.py --min-samples 5
"""

import argparse
import json
import os
from collections import defaultdict
from datetime import datetime, timezone

import joblib
import lightgbm as lgb
import numpy as np
import pandas as pd
import psycopg2
from sklearn.metrics import mean_absolute_error, r2_score
from sklearn.model_selection import train_test_split

from features import CellStats, FEATURE_NAMES, build_features, neighbour_average_baseline

DB = dict(host="localhost", port=5432, dbname="signalmap",
          user="signalmap", password="signalmap")


def load_cells(min_samples: int):
    conn = psycopg2.connect(**DB)
    try:
        df = pd.read_sql("""
            SELECT h.h3_index, h.operator_id, o.name AS operator,
                   h.quality_score, h.sample_count
            FROM hex_cells h
            JOIN operators o ON o.id = h.operator_id
        """, conn)
    finally:
        conn.close()

    if df.empty:
        raise SystemExit("hex_cells is empty. Ingest readings and run "
                         "POST /v1/admin/aggregate first.")

    # Postgres BIGINT is signed; H3 ids are unsigned 64-bit. Convert back.
    df["cell"] = df["h3_index"].apply(lambda v: format(v & 0xFFFFFFFFFFFFFFFF, "x"))

    lookups = defaultdict(dict)
    for row in df.itertuples():
        lookups[row.operator_id][row.cell] = CellStats(
            quality=row.quality_score, samples=row.sample_count)

    trusted = df[df["sample_count"] >= min_samples]
    print(f"Loaded {len(df)} cells; {len(trusted)} have >= {min_samples} samples")
    return trusted, lookups


def build_dataset(trusted: pd.DataFrame, lookups):
    """
    For each trusted cell, build features from its NEIGHBOURS and use its own
    measured quality as the label. We temporarily remove the cell from its own
    lookup so it cannot see itself -- simulating serving time exactly.
    """
    X, y, baselines, skipped = [], [], [], 0

    for row in trusted.itertuples():
        lookup = lookups[row.operator_id]
        held_out = lookup.pop(row.cell)          # <-- prevent target leakage
        try:
            feats = build_features(row.cell, row.operator_id, lookup)
            base = neighbour_average_baseline(row.cell, lookup)
            if feats is None:
                skipped += 1
                continue
            X.append(feats)
            y.append(row.quality_score)
            baselines.append(base if base is not None else np.nan)
        finally:
            lookup[row.cell] = held_out          # restore for the next iteration

    print(f"Built {len(X)} training rows ({skipped} skipped: no neighbours with data)")
    return (pd.DataFrame(X, columns=FEATURE_NAMES),
            np.array(y), np.array(baselines, dtype=float))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-samples", type=int, default=3)
    ap.add_argument("--out", default="models")
    args = ap.parse_args()

    trusted, lookups = load_cells(args.min_samples)
    X, y, baseline = build_dataset(trusted, lookups)

    if len(X) < 50:
        raise SystemExit(f"Only {len(X)} usable rows. Generate more readings "
                         f"(tools/generate_readings.py --readings 15000) and re-aggregate.")

    X_tr, X_te, y_tr, y_te, _, base_te = train_test_split(
        X, y, baseline, test_size=0.2, random_state=42)

    model = lgb.LGBMRegressor(
        n_estimators=400, learning_rate=0.05, num_leaves=31,
        min_child_samples=10, subsample=0.9, colsample_bytree=0.9,
        random_state=42, verbose=-1,
    )
    model.fit(X_tr, y_tr, eval_set=[(X_te, y_te)],
              eval_metric="l1", callbacks=[lgb.early_stopping(40, verbose=False)])

    pred = model.predict(X_te)

    model_mae = mean_absolute_error(y_te, pred)
    model_r2 = r2_score(y_te, pred)
    mask = ~np.isnan(base_te)
    base_mae = mean_absolute_error(y_te[mask], base_te[mask]) if mask.any() else float("nan")

    print("\n" + "=" * 52)
    print("EVALUATION (held-out 20% of cells)")
    print("=" * 52)
    print(f"  Baseline (neighbour avg)  MAE: {base_mae:.4f}")
    print(f"  LightGBM model            MAE: {model_mae:.4f}")
    if not np.isnan(base_mae) and base_mae > 0:
        lift = (base_mae - model_mae) / base_mae * 100
        print(f"  Improvement over baseline: {lift:+.1f}%")
        if lift <= 0:
            print("  !! Model does NOT beat the baseline. Don't deploy it as-is.")
    print(f"  R^2: {model_r2:.4f}")

    print("\nFeature importance:")
    for name, imp in sorted(zip(FEATURE_NAMES, model.feature_importances_),
                            key=lambda t: -t[1]):
        print(f"  {name:16} {imp}")

    os.makedirs(args.out, exist_ok=True)
    version = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    path = os.path.join(args.out, "model.joblib")
    joblib.dump({"model": model, "features": FEATURE_NAMES, "version": version}, path)

    meta = {
        "version": version,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "rows": int(len(X)),
        "model_mae": float(model_mae),
        "baseline_mae": None if np.isnan(base_mae) else float(base_mae),
        "r2": float(model_r2),
    }
    with open(os.path.join(args.out, "metadata.json"), "w") as f:
        json.dump(meta, f, indent=2)

    print(f"\nSaved {path} (version {version})")


if __name__ == "__main__":
    main()