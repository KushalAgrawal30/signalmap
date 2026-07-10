import "./globals.css";
import type { ReactNode } from "react";

export const metadata = { title: "SignalMap", description: "Crowdsourced network coverage map" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en"><body>{children}</body></html>
  );
}