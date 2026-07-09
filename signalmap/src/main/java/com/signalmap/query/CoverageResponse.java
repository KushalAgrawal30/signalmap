package com.signalmap.query;

public record CoverageResponse(
        String h3Index,
        double centerLat,
        double centerLng,
        String operator,
        Double qualityScore,
        Double confidence,
        Integer sampleCount,
        Source source
) {
    public enum Source { MEASURED, FALLBACK, NO_DATA }

    public static CoverageResponse noData(long cell, double[] center, String operator) {
        return new CoverageResponse(Long.toUnsignedString(cell), center[0], center[1],
                operator, null, null, 0, Source.NO_DATA);
    }
}