"use client";

import { MapContainer, TileLayer, Polygon, Marker, Tooltip, useMap, useMapEvents } from "react-leaflet";
import { useEffect, useRef, useState } from "react";
import { cellToBoundary } from "h3-js";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { getHeatmap, HeatmapCell } from "../lib/api";
import { scoreColor, confidenceOpacity } from "../lib/color";

const markerIcon = L.icon({
  iconUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png",
  iconRetinaUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png",
  shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
  iconSize: [25, 41], iconAnchor: [12, 41],
});

function HeatmapLayer({ operator }: { operator: string }) {
  const [cells, setCells] = useState<HeatmapCell[]>([]);
  const map = useMap();
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reqId = useRef(0);

  async function refresh() {
    const id = ++reqId.current;
    const b = map.getBounds();
    const data = await getHeatmap({
      minLat: b.getSouth(), minLng: b.getWest(),
      maxLat: b.getNorth(), maxLng: b.getEast(),
    }, operator, map.getZoom());
    if (id === reqId.current) setCells(data);
  }

  function scheduleRefresh() {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(refresh, 300);
  }

  useMapEvents({ moveend: scheduleRefresh });
  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [operator]);

  return (
    <>
      {cells.map((c) => {
        const base = confidenceOpacity(c.confidence);
        return (
          <Polygon
            key={c.h3Index}
            positions={cellToBoundary(c.h3Index) as [number, number][]}
            pathOptions={{ stroke: false, fillColor: scoreColor(c.qualityScore), fillOpacity: base }}
            eventHandlers={{
              mouseover: (e) => e.target.setStyle({
                stroke: true, color: "#fff", weight: 1,
                fillOpacity: Math.min(0.9, base + 0.2),
              }),
              mouseout: (e) => e.target.setStyle({ stroke: false, fillOpacity: base }),
            }}
          >
            <Tooltip sticky className="hex-tip" opacity={1}>
              <b>{operator}</b> {c.qualityScore.toFixed(2)}/5<br />
              conf {(c.confidence * 100).toFixed(0)}% · {c.sampleCount} samples
            </Tooltip>
          </Polygon>
        );
      })}
    </>
  );
}

function ClickHandler({ onPick }: { onPick: (lat: number, lng: number) => void }) {
  useMapEvents({ click: (e) => onPick(e.latlng.lat, e.latlng.lng) });
  return null;
}

function FlyTo({ target }: { target: { lat: number; lng: number } | null }) {
  const map = useMap();
  useEffect(() => {
    if (target) map.flyTo([target.lat, target.lng], Math.max(map.getZoom(), 14));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target]);
  return null;
}

export default function MapView({
  operator, marker, focus, onPick,
}: {
  operator: string;
  marker: { lat: number; lng: number } | null;
  focus: { lat: number; lng: number } | null;
  onPick: (lat: number, lng: number) => void;
}) {
  return (
    <MapContainer center={[13.0827, 80.2707]} zoom={12} style={{ height: "100%", width: "100%" }}>
      <TileLayer
        url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        attribution="&copy; OpenStreetMap &copy; CARTO"
      />
      <HeatmapLayer operator={operator} />
      <ClickHandler onPick={onPick} />
      <FlyTo target={focus} />
      {marker && <Marker position={[marker.lat, marker.lng]} icon={markerIcon} />}
    </MapContainer>
  );
}