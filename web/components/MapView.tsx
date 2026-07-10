"use client";

import { MapContainer, TileLayer, Polygon, Marker, useMap, useMapEvents } from "react-leaflet";
import { useEffect, useState } from "react";
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

  async function refresh() {
    const b = map.getBounds();
    const data = await getHeatmap({
      minLat: b.getSouth(), minLng: b.getWest(),
      maxLat: b.getNorth(), maxLng: b.getEast(),
    }, operator);
    setCells(data);
  }

  useMapEvents({ moveend: refresh });
  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [operator]);

  return (
    <>
      {cells.map((c) => (
        <Polygon
          key={c.h3Index}
          positions={cellToBoundary(c.h3Index) as [number, number][]}
          pathOptions={{
            stroke: false,
            fillColor: scoreColor(c.qualityScore),
            fillOpacity: confidenceOpacity(c.confidence),
          }}
        />
      ))}
    </>
  );
}

function ClickHandler({ onPick }: { onPick: (lat: number, lng: number) => void }) {
  useMapEvents({ click: (e) => onPick(e.latlng.lat, e.latlng.lng) });
  return null;
}

export default function MapView({
  operator, marker, onPick,
}: {
  operator: string;
  marker: { lat: number; lng: number } | null;
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
      {marker && <Marker position={[marker.lat, marker.lng]} icon={markerIcon} />}
    </MapContainer>
  );
}