package com.signalmap.ingestion;

import java.util.List;

public record IngestResult(int accepted, int rejected, List<String> sampleErrors) {}