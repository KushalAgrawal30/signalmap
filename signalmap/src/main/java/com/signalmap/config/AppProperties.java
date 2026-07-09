package com.signalmap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed access to the "signalmap.*" config tree.
 * We add sub-records (aggregation, coverage, ...) as those features arrive.
 */
@ConfigurationProperties(prefix = "signalmap")
public record AppProperties(H3 h3, Aggregation aggregation, Coverage coverage) {

    public record H3(int resolution) {}

    public record Aggregation(long tauSeconds, int windowDays, double confidenceK, String cron) {}

    public record Coverage(int minSamples) {}
}
