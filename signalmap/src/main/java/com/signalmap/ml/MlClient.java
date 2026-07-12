package com.signalmap.ml;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Calls the Python ML service.
 *
 * This is the ONLY place Spring depends on the ML sidecar, and it is designed
 * to fail safely:
 *  - short timeout: a slow prediction must never block a map query
 *  - circuit breaker: if the service is failing, stop calling it for a while
 *  - Optional return: any failure yields Optional.empty(), and the caller
 *    falls back to the neighbour average. Coverage degrades, never breaks.
 */
@Component
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);

    private final RestClient http;
    private final boolean enabled;

    public MlClient(@Value("${ml.base-url}") String baseUrl,
                    @Value("${ml.timeout-ms}") int timeoutMs,
                    @Value("${ml.enabled}") boolean enabled) {
        this.enabled = enabled;

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @CircuitBreaker(name = "mlPredict", fallbackMethod = "predictFallback")
    public Optional<MlDtos.PredictResponse> predict(String h3IndexHex, int operatorId,
                                                    List<MlDtos.Neighbour> neighbours) {
        if (!enabled || neighbours.isEmpty()) {
            return Optional.empty();
        }
        var body = new MlDtos.PredictRequest(h3IndexHex, operatorId, neighbours);
        var resp = http.post()
                .uri("/predict")
                .body(body)
                .retrieve()
                .body(MlDtos.PredictResponse.class);
        return Optional.ofNullable(resp);
    }

    /** Invoked by Resilience4j when the call fails or the circuit is open. */
    @SuppressWarnings("unused")
    private Optional<MlDtos.PredictResponse> predictFallback(String h3IndexHex, int operatorId,
                                                             List<MlDtos.Neighbour> neighbours,
                                                             Throwable t) {
        log.debug("ML predict unavailable ({}), falling back to neighbour average", t.toString());
        return Optional.empty();
    }
}