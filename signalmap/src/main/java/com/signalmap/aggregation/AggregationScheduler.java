package com.signalmap.aggregation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AggregationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AggregationScheduler.class);

    private final AggregationService service;

    public AggregationScheduler(AggregationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${signalmap.aggregation.cron}")
    public void scheduledRollup() {
        long start = System.currentTimeMillis();
        int cells = service.run();
        log.info("Aggregation roll-up complete: {} cells in {} ms",
                cells, System.currentTimeMillis() - start);
    }
}