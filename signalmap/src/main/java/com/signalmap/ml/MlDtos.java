package com.signalmap.ml;

import java.util.List;

public class MlDtos {

    public record Neighbour(String h3Index, double qualityScore, int sampleCount) {}

    public record PredictRequest(String h3Index, int operatorId, List<Neighbour> neighbours) {}

    public record PredictResponse(Double predictedScore, double confidence,
                                  String modelVersion, String source) {}
}