package com.signalmap.ingestion;

import org.springframework.stereotype.Component;

@Component
public class QualityScorer {

    private static final double RSSI_FLOOR = -105.0;
    private static final double RSSI_SPAN = 40.0;

    private static final double LOG_SPEED_FLOOR = 3.0;
    private static final double LOG_SPEED_SPAN = Math.log10(50_000) - LOG_SPEED_FLOOR;

    public double score(int rssi, int downloadKbps) {
        double signal = clamp5((rssi - RSSI_FLOOR) / RSSI_SPAN * 5.0);
        double speed = downloadKbps <= 0
                ? 0.0
                : clamp5((Math.log10(downloadKbps) - LOG_SPEED_FLOOR) / LOG_SPEED_SPAN * 5.0);
        return 0.5 * signal + 0.5 * speed;
    }

    private static double clamp5(double v) {
        if (v < 0) return 0;
        if (v > 5) return 5;
        return v;
    }
}