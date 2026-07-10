export type Source = "MEASURED" | "FALLBACK" | "NO_DATA";

export interface CoverageResponse {
  h3Index: string;
  centerLat: number;
  centerLng: number;
  operator: string;
  qualityScore: number | null;
  confidence: number | null;
  sampleCount: number | null;
  source: Source;
}

export interface HeatmapCell {
  h3Index: string;
  qualityScore: number;
  sampleCount: number;
  confidence: number;
}

export interface Bounds { minLat: number; minLng: number; maxLat: number; maxLng: number; }

export const OPERATORS = ["Airtel", "Jio", "Vi", "BSNL"];

export async function getCoverage(lat: number, lng: number, operator: string): Promise<CoverageResponse> {
  const p = new URLSearchParams({ lat: String(lat), lng: String(lng), operator });
  const r = await fetch(`/v1/coverage?${p}`);
  if (!r.ok) throw new Error(`coverage ${r.status}`);
  return r.json();
}

export async function compareCoverage(lat: number, lng: number): Promise<CoverageResponse[]> {
  const p = new URLSearchParams({ lat: String(lat), lng: String(lng) });
  const r = await fetch(`/v1/coverage/compare?${p}`);
  if (!r.ok) throw new Error(`compare ${r.status}`);
  return r.json();
}

export async function getHeatmap(b: Bounds, operator: string): Promise<HeatmapCell[]> {
  const p = new URLSearchParams({
    minLat: String(b.minLat), minLng: String(b.minLng),
    maxLat: String(b.maxLat), maxLng: String(b.maxLng), operator,
  });
  const r = await fetch(`/v1/heatmap?${p}`);
  if (!r.ok) return [];
  return r.json();
}