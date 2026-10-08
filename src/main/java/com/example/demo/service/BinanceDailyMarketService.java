package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BinanceDailyMarketService {

    private static final long DAY_MILLIS = 86_400_000L;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public BinanceDailyMarketService(
            @Qualifier("restTemplate")
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${binance.api.base-url}")
            String baseUrl) {

        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public DailyOhlcvResponse fetchDaily(
            String symbol,
            LocalDate date,
            long startTime,
            long endTime) {

        String url = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/api/v3/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", "1d")
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime - 1)
                .queryParam("limit", 1)
                .build()
                .encode()
                .toUriString();

        try {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            url,
                            String.class);

            JsonNode root =
                    objectMapper.readTree(response.getBody());

            if (root == null
                    || !root.isArray()
                    || root.isEmpty()) {

                throw unavailable(
                        "Binance.US 没有返回该日期的行情",
                        null);
            }

            JsonNode candle = root.get(0);

            if (!candle.isArray()
                    || candle.size() < 9
                    || candle.get(0).asLong() != startTime
                    || candle.get(6).asLong() != endTime - 1) {

                throw unavailable(
                        "Binance.US 返回的日线时间不正确",
                        null);
            }

            return new DailyOhlcvResponse(
                    symbol,
                    date,
                    "UTC",
                    candle.get(0).asLong(),
                    candle.get(6).asLong(),
                    decimal(candle, 1),
                    decimal(candle, 2),
                    decimal(candle, 3),
                    decimal(candle, 4),
                    decimal(candle, 5),
                    candle.get(8).asLong(),
                    "BINANCE_US");

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientException exception) {
            throw unavailable(
                    "请求 Binance.US 失败",
                    exception);

        } catch (Exception exception) {
            throw unavailable(
                    "Binance.US 返回的数据无法解析",
                    exception);
        }
    }

    /**
     * Fetches the exchange's own daily candles whose open time falls in [startTime, endTime),
     * keyed by open time. Used as the independent reference for reconciliation.
     */
    public Map<Long, DailyOhlcvResponse> fetchDailyRange(
            String symbol,
            long startTime,
            long endTime) {

        String url = UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/api/v3/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", "1d")
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime - 1)
                .queryParam("limit", (endTime - startTime) / DAY_MILLIS)
                .build()
                .encode()
                .toUriString();

        try {
            JsonNode root = objectMapper.readTree(
                    restTemplate.getForEntity(url, String.class).getBody());

            if (root == null || !root.isArray()) {
                throw unavailable(
                        "Binance.US 返回的日线格式不正确",
                        null);
            }

            Map<Long, DailyOhlcvResponse> candles = new LinkedHashMap<>();
            for (JsonNode candle : root) {
                if (!candle.isArray() || candle.size() < 9) {
                    throw unavailable(
                            "Binance.US 返回的日线格式不正确",
                            null);
                }
                long openTime = candle.get(0).asLong();
                candles.put(openTime, new DailyOhlcvResponse(
                        symbol,
                        Instant.ofEpochMilli(openTime)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate(),
                        "UTC",
                        openTime,
                        candle.get(6).asLong(),
                        decimal(candle, 1),
                        decimal(candle, 2),
                        decimal(candle, 3),
                        decimal(candle, 4),
                        decimal(candle, 5),
                        candle.get(8).asLong(),
                        "BINANCE_US"));
            }
            return candles;

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientException exception) {
            throw unavailable(
                    "请求 Binance.US 失败",
                    exception);

        } catch (Exception exception) {
            throw unavailable(
                    "Binance.US 返回的数据无法解析",
                    exception);
        }
    }

    private BigDecimal decimal(
            JsonNode candle,
            int index) {

        return new BigDecimal(
                candle.get(index).asText());
    }

    private ResponseStatusException unavailable(
            String message,
            Throwable cause) {

        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                message,
                cause);
    }
}