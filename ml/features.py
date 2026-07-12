"""
Feature engineering for SignalMap's cell-quality model.

CRITICAL DESIGN RULE
--------------------
Features for a cell describe only its *surroundings* -- never its own readings.
That's what lets the model predict cells that have NO data at serving time.
If we leaked the cell's own quality_score into the features, training accuracy
would look great and the model would be useless in production (target leakage).

This module is imported by BOTH training and serving, so features are computed
identically in both places. Train/serve skew is one of the most common ways
real ML systems silently break.
"""

from dataclasses import dataclass
from typing import Dict, Optional

import h3
import numpy as np

FEATURE_NAMES = [
    "operator_id",
    "ring1_mean",       # mean quality of the 6 immediate neighbours
    "ring1_max",
    "ring1_min",
    "ring1_count",      # how many of the 6 have data (density)
    "ring1_samples",    # total readings backing those neighbours
    "ring2_mean",       # next ring out (12 cells)
    "ring2_count",
    "neighbour_std",    # spread -> is this a boundary/edge area?
    "lat",
    "lng",
]


@dataclass
class CellStats:
    quality: float
    samples: int


def _ring_stats(cell: str, k: int, lookup: Dict[str, CellStats]) -> dict:
    try:
        ring = h3.grid_ring(cell, k)
    except Exception:
        ring = []

    qualities, samples = [], []
    for c in ring:
        s = lookup.get(c)
        if s is not None:
            qualities.append(s.quality)
            samples.append(s.samples)

    if not qualities:
        return {"mean": np.nan, "max": np.nan, "min": np.nan,
                "count": 0, "samples": 0, "values": []}

    return {
        "mean": float(np.mean(qualities)),
        "max": float(np.max(qualities)),
        "min": float(np.min(qualities)),
        "count": len(qualities),
        "samples": int(np.sum(samples)),
        "values": qualities,
    }


def build_features(cell: str, operator_id: int, lookup: Dict[str, CellStats]) -> Optional[list]:
    """
    Build the feature vector for one (cell, operator).
    `lookup` maps h3 cell -> CellStats for THIS operator only.
    Returns None if the cell is completely isolated -- nothing to predict from.
    """
    r1 = _ring_stats(cell, 1, lookup)
    r2 = _ring_stats(cell, 2, lookup)

    if r1["count"] == 0 and r2["count"] == 0:
        return None

    all_vals = r1["values"] + r2["values"]
    neighbour_std = float(np.std(all_vals)) if len(all_vals) > 1 else 0.0
    lat, lng = h3.cell_to_latlng(cell)

    return [
        operator_id,
        r1["mean"], r1["max"], r1["min"], r1["count"], r1["samples"],
        r2["mean"], r2["count"],
        neighbour_std,
        lat, lng,
    ]


def neighbour_average_baseline(cell: str, lookup: Dict[str, CellStats]) -> Optional[float]:
    """
    The baseline the model must beat: sample-weighted average of ring-1
    neighbours -- exactly what Spring's current FALLBACK branch does. If the
    model can't beat this, the ML layer isn't worth deploying.
    """
    try:
        ring = h3.grid_ring(cell, 1)
    except Exception:
        return None

    num, den = 0.0, 0
    for c in ring:
        s = lookup.get(c)
        if s is not None:
            num += s.quality * s.samples
            den += s.samples
    return num / den if den > 0 else None