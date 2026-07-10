"use client";

import dynamic from "next/dynamic";
import { useState, FormEvent } from "react";
import { getCoverage, compareCoverage, CoverageResponse, OPERATORS } from "../../lib/api";

const MapView = dynamic(() => import("../../components/MapView"), { ssr: false });

export default function Home() {
  const [operator, setOperator] = useState("Jio");
  const [lat, setLat] = useState("13.0827");
  const [lng, setLng] = useState("80.2707");
  const [result, setResult] = useState<CoverageResponse | null>(null);
  const [compare, setCompare] = useState<CoverageResponse[]>([]);
  const [marker, setMarker] = useState<{ lat: number; lng: number } | null>(null);
  const [loading, setLoading] = useState(false);

  async function query(la: number, ln: number, op: string) {
    if (Number.isNaN(la) || Number.isNaN(ln)) return;
    setLoading(true);
    setMarker({ lat: la, lng: ln });
    try {
      const [cov, cmp] = await Promise.all([getCoverage(la, ln, op), compareCoverage(la, ln)]);
      setResult(cov);
      setCompare(cmp);
    } catch {
      setResult(null);
      setCompare([]);
    } finally {
      setLoading(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    query(parseFloat(lat), parseFloat(lng), operator);
  }

  function onPick(la: number, ln: number) {
    setLat(la.toFixed(6));
    setLng(ln.toFixed(6));
    query(la, ln, operator);
  }

  return (
    <main className="layout">
      <div className="map">
        <MapView operator={operator} marker={marker} onPick={onPick} />
      </div>

      <aside className="panel">
        <h1>SignalMap</h1>
        <p className="sub">Click the map or enter coordinates to check coverage.</p>

        <form onSubmit={onSubmit} className="form">
          <div className="row">
            <label>Latitude<input value={lat} onChange={(e) => setLat(e.target.value)} /></label>
            <label>Longitude<input value={lng} onChange={(e) => setLng(e.target.value)} /></label>
          </div>
          <label>Operator
            <select value={operator} onChange={(e) => setOperator(e.target.value)}>
              {OPERATORS.map((o) => <option key={o}>{o}</option>)}
            </select>
          </label>
          <button type="submit">{loading ? "Checking…" : "Check coverage"}</button>
        </form>

        {result && <ResultCard r={result} />}
        {compare.length > 0 && <CompareList rows={compare} />}
        <Legend />
      </aside>
    </main>
  );
}

function badgeClass(source: string) {
  if (source === "MEASURED") return "badge measured";
  if (source === "FALLBACK") return "badge fallback";
  return "badge nodata";
}

function ResultCard({ r }: { r: CoverageResponse }) {
  return (
    <div className="card">
      <div className="card-head">
        <span className="op">{r.operator}</span>
        <span className={badgeClass(r.source)}>{r.source}</span>
      </div>
      {r.source === "NO_DATA" ? (
        <p className="muted">No readings near this location yet.</p>
      ) : (
        <>
          <div className="score">
            <div className="score-num">{r.qualityScore?.toFixed(2)}<span>/5</span></div>
            <div className="bar"><div style={{ width: `${((r.qualityScore ?? 0) / 5) * 100}%` }} /></div>
          </div>
          <div className="meta">
            <span>Confidence {((r.confidence ?? 0) * 100).toFixed(0)}%</span>
            <span>{r.sampleCount} samples</span>
          </div>
          {r.source === "FALLBACK" && (
            <p className="muted small">Estimated from neighbouring cells — no direct readings here.</p>
          )}
        </>
      )}
    </div>
  );
}

function CompareList({ rows }: { rows: CoverageResponse[] }) {
  return (
    <div className="compare">
      <h3>Operator comparison</h3>
      {rows.map((r) => (
        <div className="crow" key={r.operator}>
          <span className="cop">{r.operator}</span>
          <div className="cbar"><div style={{ width: `${((r.qualityScore ?? 0) / 5) * 100}%` }} /></div>
          <span className="cval">{r.qualityScore?.toFixed(1)}</span>
        </div>
      ))}
    </div>
  );
}

function Legend() {
  return (
    <div className="legend">
      <div className="legend-bar" />
      <div className="legend-labels"><span>weak</span><span>strong</span></div>
      <p className="legend-note">Faded cells = lower confidence (fewer readings)</p>
    </div>
  );
}