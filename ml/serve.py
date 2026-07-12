#!/usr/bin/env python3
"""
SignalMap ML prediction service.

Stateless by design: loads the trained model artifact at startup and answers
/predict from the features the CALLER supplies. It never touches Postgres --
Spring already has the DB and cache, so it gathers the neighbourhood data and
passes it in. That keeps this service a pure function (features -> prediction),
which makes it trivially scalable and testable.

Run:
    uvicorn serve:app --port 8000
"""

import os
from contextlib import asynccontextmanager
from typing import Dict, List, Optional

import joblib
import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel, Field

from features import CellStats, FEATURE_NAMES, build_features, neighbour_average_baseline

MODEL_PATH = os.getenv("MODEL_PATH", "models/model.joblib")

_model = None
_version = "unloaded"


def load_model():
    """Load the artifact written by train.py. Called at startup and by /reload."""
    global _model, _version
    bundle = joblib.load(MODEL_PATH)
    _model = bundle["model"]
    _version = bundle["version"]
    return _version


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load the model once, at startup -- never per request.
    try:
        print(f"Loaded model version {load_model()}")
    except FileNotFoundError:
        print(f"WARNING: no model at {MODEL_PATH}. Run train.py first. "
              f"/predict will degrade to the neighbour-average fallback.")
    yield


app = FastAPI(title="SignalMap ML", version="1.0", lifespan=lifespan)


# ---------- request / response contracts ----------

class Neighbour(BaseModel):
    """One neighbouring cell that HAS data, as supplied by the caller."""
    h3Index: str = Field(..., description="H3 cell id, hex string")
    qualityScore: float
    sampleCount: int


class PredictRequest(BaseModel):
    h3Index: str = Field(..., description="target cell to predict, hex string")
    operatorId: int
    neighbours: List[Neighbour] = Field(
        default_factory=list,
        description="cells within 2 rings of the target that have data")


class PredictResponse(BaseModel):
    predictedScore: Optional[float]
    confidence: float
    modelVersion: str
    source: str  # PREDICTED | FALLBACK | NO_DATA


# ---------- endpoints ----------

@app.get("/healthz")
def healthz():
    return {"status": "ok" if _model is not None else "no_model",
            "modelVersion": _version}


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest) -> PredictResponse:
    lookup: Dict[str, CellStats] = {
        n.h3Index: CellStats(quality=n.qualityScore, samples=n.sampleCount)
        for n in req.neighbours
    }

    # Nothing nearby -> be honest, don't invent a number.
    if not lookup:
        return PredictResponse(predictedScore=None, confidence=0.0,
                               modelVersion=_version, source="NO_DATA")

    # No model loaded -> degrade to the neighbour average rather than failing.
    if _model is None:
        base = neighbour_average_baseline(req.h3Index, lookup)
        if base is None:
            return PredictResponse(predictedScore=None, confidence=0.0,
                                   modelVersion=_version, source="NO_DATA")
        return PredictResponse(predictedScore=round(base, 2),
                               confidence=_confidence(lookup),
                               modelVersion=_version, source="FALLBACK")

    feats = build_features(req.h3Index, req.operatorId, lookup)
    if feats is None:
        return PredictResponse(predictedScore=None, confidence=0.0,
                               modelVersion=_version, source="NO_DATA")

    X = pd.DataFrame([feats], columns=FEATURE_NAMES)
    score = float(_model.predict(X)[0])
    score = max(0.0, min(5.0, score))  # clamp to the valid range

    return PredictResponse(predictedScore=round(score, 2),
                           confidence=_confidence(lookup),
                           modelVersion=_version, source="PREDICTED")


@app.post("/reload")
def reload_model():
    """Pick up a newly trained artifact without restarting the process."""
    try:
        v = load_model()
        return {"status": "ok", "modelVersion": v}
    except FileNotFoundError:
        return {"status": "error", "message": f"no model at {MODEL_PATH}"}


def _confidence(lookup: Dict[str, CellStats]) -> float:
    """
    A prediction is never as trustworthy as a direct measurement, so cap it
    below 1.0. Grows with how much neighbouring evidence backs it.
    """
    total = sum(s.samples for s in lookup.values())
    n_cells = len(lookup)
    evidence = min(1.0, total / 40.0) * min(1.0, n_cells / 6.0)
    return round(0.15 + 0.55 * evidence, 2)  # 0.15 .. 0.70