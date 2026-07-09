package com.signalmap.query;

public record HeatmapCell(
        String h3Index,
        double qualityScore,
        int sampleCount,
        double confidence
) {}