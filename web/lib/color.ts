const STOPS: [number, [number, number, number]][] = [
  [0.0, [178, 24, 43]],
  [0.5, [253, 174, 97]],
  [1.0, [26, 152, 80]],
];

export function scoreColor(score: number): string {
  const t = Math.max(0, Math.min(1, score / 5));
  for (let i = 0; i < STOPS.length - 1; i++) {
    const [t0, c0] = STOPS[i];
    const [t1, c1] = STOPS[i + 1];
    if (t <= t1) {
      const f = (t - t0) / (t1 - t0);
      const c = c0.map((v, k) => Math.round(v + (c1[k] - v) * f));
      return `rgb(${c[0]},${c[1]},${c[2]})`;
    }
  }
  return "rgb(26,152,80)";
}

export function confidenceOpacity(confidence: number): number {
  return 0.18 + 0.55 * Math.max(0, Math.min(1, confidence));
}