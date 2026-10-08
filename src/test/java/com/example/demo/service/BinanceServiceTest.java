package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BinanceServiceTest {

    /** The exchange's clock in these tests. */
    private static final long EXCHANGE_NOW = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final String KLINES = "/api/v3/klines";

    private static final String TWO_CLOSED_CANDLES = """
            [
              [
                1697068382000,
                "100.10",
                "105.20",
                "99.50",
                "102.30",
                "12.50",
                1697068439999,
                "0",
                30
              ],
              [
                1697068442000,
                "102.30",
                "108.00",
                "101.00",
                "107.50",
                "20.00",
                1697068499999,
                "0",
                45
              ]
            ]
            """;

    @Mock
    private TradeDataProducer tradeDataProducer;

    @Mock
    private RestTemplate restTemplate;

    private BinanceService binanceService;

    @BeforeEach
    void setUp() {
        binanceService = new BinanceService(
                tradeDataProducer,
                restTemplate,
                new ObjectMapper(),
                "https://api.binance.us",
                Duration.ofDays(366),
                2,
                3,
                Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        binanceService.shutdown();
    }

    @Test
    public void test_load_success_insertTwoRecords() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = startTime + 2 * 60000L;

        klinesReturn(TWO_CLOSED_CANDLES);
        exchangeTimeIs(EXCHANGE_NOW);
        producerAcknowledges();

        int result = binanceService.load(symbol, startTime, endTime);

        Assertions.assertEquals(2, result);

        ArgumentCaptor<TradeData> captor =
                ArgumentCaptor.forClass(TradeData.class);

        verify(tradeDataProducer, times(2)).send(captor.capture());

        List<TradeData> savedData = captor.getAllValues();
        TradeData first = savedData.get(0);

        Assertions.assertEquals(1697068382000L, first.getOpenTime());
        Assertions.assertEquals(new BigDecimal("100.10"), first.getOpenPrice());
        Assertions.assertEquals(new BigDecimal("105.20"), first.getHighPrice());
        Assertions.assertEquals(new BigDecimal("99.50"), first.getLowPrice());
        Assertions.assertEquals(new BigDecimal("102.30"), first.getClosePrice());
        Assertions.assertEquals(new BigDecimal("12.50"), first.getVolume());
        Assertions.assertEquals(1697068439999L, first.getCloseTime());
        Assertions.assertEquals(30L, first.getNumsOfTrade());
        Assertions.assertEquals("BTCUSDT", first.getSymbol());

        verify(restTemplate).getForEntity(
                contains("symbol=BTCUSDT"),
                eq(String.class)
        );
    }

    @Test
    public void test_load_emptyApiResponse_returnZero() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = startTime + 60000L;

        klinesReturn("[]");
        exchangeTimeIs(EXCHANGE_NOW);

        int result = binanceService.load(symbol, startTime, endTime);

        Assertions.assertEquals(0, result);
        verify(tradeDataProducer, never()).send(any());
    }

    @Test
    public void test_load_invalidJson_reportsFailedBatch() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = startTime + 60000L;
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalError = System.err;

        klinesReturn("invalid json");
        exchangeTimeIs(EXCHANGE_NOW);

        MarketDataLoadException exception;
        try {
            System.setErr(new PrintStream(errorOutput));
            exception = Assertions.assertThrows(
                    MarketDataLoadException.class,
                    () -> binanceService.load(symbol, startTime, endTime));
        } finally {
            System.setErr(originalError);
        }

        Assertions.assertEquals(0, exception.getSentRecords());
        Assertions.assertEquals(1, exception.getFailedBatches());
        Assertions.assertFalse(errorOutput.toString(StandardCharsets.UTF_8).contains("JsonParseException"));
        verify(tradeDataProducer, never()).send(any());
    }

    @Test
    public void test_load_skipsCandleTheExchangeHasNotClosed() {
        long closedOpen = EXCHANGE_NOW - 120_000L;
        long formingOpen = EXCHANGE_NOW - 30_000L;
        klinesReturn(klines(closedOpen, formingOpen));
        exchangeTimeIs(EXCHANGE_NOW);
        producerAcknowledges();

        int result = binanceService.load("BTCUSDT", closedOpen, formingOpen + 60_000L);

        Assertions.assertEquals(1, result);
        ArgumentCaptor<TradeData> captor = ArgumentCaptor.forClass(TradeData.class);
        verify(tradeDataProducer).send(captor.capture());
        Assertions.assertEquals(closedOpen, captor.getValue().getOpenTime());
    }

    @Test
    public void test_load_usesExchangeClockWithSafetyMargin() {
        // Closed one second ago by the exchange's clock: inside the two-second margin, so not yet trusted.
        long justClosedOpen = EXCHANGE_NOW - 61_000L;
        klinesReturn(klines(justClosedOpen));
        exchangeTimeIs(EXCHANGE_NOW);

        int result = binanceService.load("BTCUSDT", justClosedOpen, justClosedOpen + 60_000L);

        Assertions.assertEquals(0, result);
        verify(tradeDataProducer, never()).send(any());
    }

    @Test
    public void test_load_failsWithoutExchangeTime() {
        doThrow(HttpServerErrorException.create(
                HttpStatus.SERVICE_UNAVAILABLE, "unavailable", HttpHeaders.EMPTY, null, null))
                .when(restTemplate).getForEntity(contains("/api/v3/time"), eq(String.class));

        MarketDataLoadException exception = Assertions.assertThrows(
                MarketDataLoadException.class,
                () -> binanceService.load("BTCUSDT", 1697068382000L, 1697068382000L + 60000L));

        Assertions.assertEquals(1, exception.getFailedBatches());
        verify(restTemplate, never()).getForEntity(contains(KLINES), eq(String.class));
        verify(tradeDataProducer, never()).send(any());
    }

    @Test
    public void test_load_retriesServerErrorThenSucceeds() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", HttpHeaders.EMPTY, null, null))
                .thenReturn(ResponseEntity.ok(TWO_CLOSED_CANDLES));
        exchangeTimeIs(EXCHANGE_NOW);
        producerAcknowledges();

        int result = binanceService.load("BTCUSDT", 1697068382000L, 1697068382000L + 2 * 60000L);

        Assertions.assertEquals(2, result);
        verify(restTemplate, times(2)).getForEntity(contains(KLINES), eq(String.class));
    }

    @Test
    public void test_load_givesUpAfterMaxAttempts() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "slow down", HttpHeaders.EMPTY, null, null));
        exchangeTimeIs(EXCHANGE_NOW);

        MarketDataLoadException exception = Assertions.assertThrows(
                MarketDataLoadException.class,
                () -> binanceService.load("BTCUSDT", 1697068382000L, 1697068382000L + 60000L));

        Assertions.assertEquals(1, exception.getFailedBatches());
        verify(restTemplate, times(3)).getForEntity(contains(KLINES), eq(String.class));
        verify(tradeDataProducer, never()).send(any());
    }

    @Test
    public void test_load_doesNotRetryClientError() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, null, null));
        exchangeTimeIs(EXCHANGE_NOW);

        Assertions.assertThrows(
                MarketDataLoadException.class,
                () -> binanceService.load("BTCUSDT", 1697068382000L, 1697068382000L + 60000L));

        verify(restTemplate, times(1)).getForEntity(contains(KLINES), eq(String.class));
    }

    @Test
    public void test_load_reportsFailedKafkaSend() {
        klinesReturn(TWO_CLOSED_CANDLES);
        exchangeTimeIs(EXCHANGE_NOW);
        when(tradeDataProducer.send(any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        MarketDataLoadException exception = Assertions.assertThrows(
                MarketDataLoadException.class,
                () -> binanceService.load("BTCUSDT", 1697068382000L, 1697068382000L + 2 * 60000L));

        Assertions.assertEquals(0, exception.getSentRecords());
    }

    @Test
    public void test_load_rejectsSymbolThatCouldInjectQueryParameters() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> binanceService.load("BTCUSDT&limit=1", 1697068382000L, 1697068382000L + 60000L));

        verifyNoInteractions(restTemplate, tradeDataProducer);
    }

    @Test
    public void test_load_rejectsInvalidRanges() {
        long start = 1697068382000L;

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> binanceService.load("BTCUSDT", start, start));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> binanceService.load("BTCUSDT", start, start + Duration.ofDays(367).toMillis()));

        verifyNoInteractions(restTemplate, tradeDataProducer);
    }

    private void klinesReturn(String body) {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    /** Registered after the other stubs so it wins for the server-time URL; doReturn avoids calling them. */
    private void exchangeTimeIs(long serverTime) {
        doReturn(ResponseEntity.ok("{\"serverTime\":" + serverTime + "}"))
                .when(restTemplate).getForEntity(contains("/api/v3/time"), eq(String.class));
    }

    private void producerAcknowledges() {
        when(tradeDataProducer.send(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static String klines(long... openTimes) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < openTimes.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("[%d,\"1\",\"1\",\"1\",\"1\",\"1\",%d,\"0\",1]"
                    .formatted(openTimes[i], openTimes[i] + 59_999L));
        }
        return json.append(']').toString();
    }
}
