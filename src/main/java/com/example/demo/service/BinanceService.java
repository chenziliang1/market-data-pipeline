package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class BinanceService {

    private static final Logger logger = LoggerFactory.getLogger(BinanceService.class);

    private static final int MAX_PER_REQUEST = 1000;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]{2,20}");
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private final TradeDataProducer tradeDataProducer;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String baseUrl;
    private final Duration maxRange;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final ExecutorService executor;

    public BinanceService(
            TradeDataProducer tradeDataProducer,
            @Qualifier("restTemplate") RestTemplate restTemplate,
            ObjectMapper mapper,
            Clock clock,
            @Value("${binance.api.base-url}") String baseUrl,
            @Value("${app.binance.max-range:P366D}") Duration maxRange,
            @Value("${app.binance.max-concurrent-requests:4}") int maxConcurrentRequests,
            @Value("${app.binance.max-attempts:3}") int maxAttempts,
            @Value("${app.binance.retry-backoff:PT1S}") Duration retryBackoff) {
        this.tradeDataProducer = tradeDataProducer;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.maxRange = maxRange;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoff = retryBackoff;

        // Bounded pool for blocking HTTP calls, instead of the shared ForkJoinPool behind parallel streams.
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, maxConcurrentRequests), runnable -> {
            Thread thread = new Thread(runnable, "binance-fetch-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Fetches closed one-minute candles in [startTime, endTime) and publishes them to Kafka.
     *
     * @return the number of candles Kafka acknowledged
     * @throws IllegalArgumentException if the symbol or range is invalid
     * @throws MarketDataLoadException  if any batch failed; candles from other batches may already be sent
     */
    public int load(String symbol, Long startTime, Long endTime) {
        String normalizedSymbol = normalizeSymbol(symbol);
        validateRange(startTime, endTime);

        long totalMinutes = ceilDiv(endTime - startTime, ONE_MINUTE_MS);
        int numberOfRequests = (int) ceilDiv(totalMinutes, MAX_PER_REQUEST);
        long now = clock.millis();

        List<Future<Integer>> batches = new ArrayList<>(numberOfRequests);
        for (int chunkIndex = 0; chunkIndex < numberOfRequests; chunkIndex++) {
            long batchStart = startTime + (long) chunkIndex * MAX_PER_REQUEST * ONE_MINUTE_MS;
            long batchEnd = Math.min(batchStart + MAX_PER_REQUEST * ONE_MINUTE_MS, endTime);
            batches.add(executor.submit(() -> loadBatch(normalizedSymbol, batchStart, batchEnd, now)));
        }

        int sent = 0;
        int failed = 0;
        for (Future<Integer> batch : batches) {
            try {
                sent += batch.get();
            } catch (ExecutionException e) {
                failed++;
                logger.warn("Failed to load a Binance batch for {}: {}", normalizedSymbol, firstLine(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed++;
            }
        }

        if (failed > 0) {
            throw new MarketDataLoadException(sent, failed, numberOfRequests);
        }
        return sent;
    }

    private int loadBatch(String symbol, long batchStart, long batchEnd, long now) throws Exception {
        String url = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/api/v3/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", "1m")
                .queryParam("startTime", batchStart)
                .queryParam("endTime", batchEnd - 1)
                .queryParam("limit", ceilDiv(batchEnd - batchStart, ONE_MINUTE_MS))
                .build()
                .encode()
                .toUriString();

        JsonNode rootNode = mapper.readTree(fetchWithRetry(url));
        if (rootNode == null || !rootNode.isArray()) {
            throw new IllegalStateException("Binance response is not a JSON array");
        }

        List<TradeData> closedCandles = new ArrayList<>();
        int unclosed = 0;
        for (JsonNode klineNode : rootNode) {
            TradeData data = mapToTradeData(klineNode, symbol, batchStart, batchEnd);
            // A candle that has not closed yet is still changing; storing it would keep partial values.
            if (data.getCloseTime() >= now) {
                unclosed++;
                continue;
            }
            closedCandles.add(data);
        }
        if (unclosed > 0) {
            logger.info("Skipped {} unclosed candle(s) for {}", unclosed, symbol);
        }

        CompletableFuture<?>[] sends = closedCandles.stream()
                .map(tradeDataProducer::send)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        return closedCandles.size();
    }

    private String fetchWithRetry(String url) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return restTemplate.getForEntity(url, String.class).getBody();
            } catch (HttpClientErrorException.TooManyRequests
                     | HttpServerErrorException
                     | ResourceAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                Duration delay = retryDelay(e, attempt);
                logger.warn("Binance request failed (attempt {}/{}), retrying in {} ms: {}",
                        attempt, maxAttempts, delay.toMillis(), firstLine(e));
                Thread.sleep(delay.toMillis());
            }
        }
    }

    private Duration retryDelay(RestClientException exception, int attempt) {
        if (exception instanceof HttpClientErrorException.TooManyRequests tooManyRequests
                && tooManyRequests.getResponseHeaders() != null) {
            String retryAfter = tooManyRequests.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                try {
                    Duration requested = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                    return requested.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested;
                } catch (NumberFormatException ignored) {
                    // Fall back to exponential backoff.
                }
            }
        }
        return retryBackoff.multipliedBy(1L << (attempt - 1));
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("symbol must be 2-20 letters or digits, for example BTCUSDT");
        }
        return normalized;
    }

    private void validateRange(Long startTime, Long endTime) {
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("startTime and endTime are required");
        }
        if (startTime < 0 || startTime >= endTime) {
            throw new IllegalArgumentException("startTime must be non-negative and earlier than endTime");
        }
        if (endTime - startTime > maxRange.toMillis()) {
            throw new IllegalArgumentException("range must not exceed " + maxRange.toDays() + " days");
        }
    }

    private TradeData mapToTradeData(JsonNode klineNode, String symbol, Long startTime, Long endTime) {
        if (!klineNode.isArray() || klineNode.size() < 9) {
            throw new IllegalStateException("Unexpected kline format from Binance");
        }
        TradeData data = new TradeData();
        data.setOpenTime(klineNode.get(0).asLong());
        data.setOpenPrice(new BigDecimal(klineNode.get(1).asText()));
        data.setHighPrice(new BigDecimal(klineNode.get(2).asText()));
        data.setLowPrice(new BigDecimal(klineNode.get(3).asText()));
        data.setClosePrice(new BigDecimal(klineNode.get(4).asText()));
        data.setVolume(new BigDecimal(klineNode.get(5).asText()));
        data.setCloseTime(klineNode.get(6).asLong());
        data.setNumsOfTrade(klineNode.get(8).asLong());
        data.setSymbol(symbol);
        data.setStartTime(startTime);
        data.setEndTime(endTime);
        return data;
    }

    private static long ceilDiv(long dividend, long divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }

    private static String firstLine(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage().split("\\R", 2)[0];
    }
}
