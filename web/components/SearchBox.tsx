"use client";

import { useEffect, useState } from "react";
import { geocode, GeoResult } from "../lib/api";

export default function SearchBox({
  onSelect,
}: {
  onSelect: (lat: number, lng: number, label: string) => void;
}) {
  const [q, setQ] = useState("");
  const [results, setResults] = useState<GeoResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (q.trim().length < 3) {
      setResults([]);
      return;
    }
    setSearching(true);
    const t = setTimeout(async () => {
      try {
        const r = await geocode(q.trim());
        setResults(r);
        setOpen(true);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, 350);
    return () => clearTimeout(t);
  }, [q]);

  function pick(r: GeoResult) {
    setQ(r.label.split(",")[0]);
    setOpen(false);
    onSelect(r.lat, r.lng, r.label);
  }

  return (
    <div className="search">
      <input
        placeholder="Search a place…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onFocus={() => results.length > 0 && setOpen(true)}
      />
      {searching && <span className="search-spin" />}
      {open && results.length > 0 && (
        <ul className="search-results">
          {results.map((r, i) => (
            <li key={i} onClick={() => pick(r)}>{r.label}</li>
          ))}
        </ul>
      )}
    </div>
  );
}