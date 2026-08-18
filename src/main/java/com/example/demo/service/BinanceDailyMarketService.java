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
import java.time.LocalDate;

@Service
public class BinanceDailyMarketService {

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