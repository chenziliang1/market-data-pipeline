package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.DailyReconciliationReport;
import com.example.demo.dto.DailyReconciliationReport.Status;
import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.mapper.AggregationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyReconciliationServiceTest {

    private static final LocalDate DAY_1 = LocalDate.of(2024, 1, 1);
    private static final LocalDate DAY_2 = LocalDate.of(2024, 1, 2);
    private static final LocalDate DAY_3 = LocalDate.of(2024, 1, 3);

    @Mock
    private AggregationMapper aggregationMapper;

    @Mock
    private BinanceDailyMarketService binanceDailyMarketService;

    private DailyReconciliationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new DailyReconciliationService(aggregationMapper, binanceDailyMarketService, clock);
    }

    @Test
    void classifiesMatchingMismatchedAndIncompleteDays() {
        when(aggregationMapper.findAggregated(eq("BTCUSDT"), anyLong(), anyLong(), eq("day")))
                .thenReturn(List.of(
                        stored(DAY_1, "100", "1440"),
                        stored(DAY_2, "100", "1440"),
                        stored(DAY_3, "100", "1439")));
        when(binanceDailyMarketService.fetchDailyRange(anyString(), anyLong(), anyLong()))
                .thenReturn(Map.of(
                        startOf(DAY_1), exchange(DAY_1, "100.00000000"),
                        startOf(DAY_2), exchange(DAY_2, "101"),
                        startOf(DAY_3), exchange(DAY_3, "100")));

        DailyReconciliationReport report = service.reconcile("btcusdt", DAY_1, DAY_3);

        assertThat(report.checkedDays()).isEqualTo(3);
        assertThat(report.matchedDays()).isEqualTo(1);
        assertThat(report.mismatchedDays()).isEqualTo(1);
        assertThat(report.incompleteDays()).isEqualTo(1);
        assertThat(report.discrepancies())
                .extracting(DailyReconciliationReport.DayDiscrepancy::status)
                .containsExactly(Status.MISMATCH, Status.INCOMPLETE);
        assertThat(report.discrepancies().get(0).differences())
                .singleElement()
                .satisfies(difference -> {
                    assertThat(difference.field()).isEqualTo("close");
                    assertThat(difference.stored()).isEqualTo("100");
                    assertThat(difference.exchange()).isEqualTo("101");
                });
    }

    @Test
    void reportsDayTheExchangeDidNotReturn() {
        when(aggregationMapper.findAggregated(eq("BTCUSDT"), anyLong(), anyLong(), eq("day")))
                .thenReturn(List.of(stored(DAY_1, "100", "1440")));
        when(binanceDailyMarketService.fetchDailyRange(anyString(), anyLong(), anyLong()))
                .thenReturn(Map.of());

        DailyReconciliationReport report = service.reconcile("BTCUSDT", DAY_1, DAY_1);

        assertThat(report.missingOnExchangeDays()).isEqualTo(1);
        assertThat(report.discrepancies()).singleElement()
                .extracting(DailyReconciliationReport.DayDiscrepancy::status)
                .isEqualTo(Status.MISSING_ON_EXCHANGE);
    }

    @Test
    void rejectsDaysThatHaveNotEnded() {
        assertThatThrownBy(() -> service.reconcile("BTCUSDT", DAY_1, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcile("BTCUSDT", DAY_2, DAY_1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcile("BTCUSDT", DAY_1, DAY_1.plusDays(366)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(aggregationMapper, binanceDailyMarketService);
    }

    private static AggregatedTradeData stored(LocalDate date, String close, String candleCount) {
        AggregatedTradeData row = new AggregatedTradeData();
        row.setBucketStartTime(startOf(date));
        row.setOpenPrice(new BigDecimal("90"));
        row.setHighPrice(new BigDecimal("110"));
        row.setLowPrice(new BigDecimal("80"));
        row.setClosePrice(new BigDecimal(close));
        row.setVolume(new BigDecimal("12.5"));
        row.setNumsOfTrade(42L);
        row.setCandleCount(Long.parseLong(candleCount));
        return row;
    }

    private static DailyOhlcvResponse exchange(LocalDate date, String close) {
        return new DailyOhlcvResponse(
                "BTCUSDT", date, "UTC", startOf(date), startOf(date.plusDays(1)) - 1,
                new BigDecimal("90"), new BigDecimal("110"), new BigDecimal("80"), new BigDecimal(close),
                new BigDecimal("12.50000000"), 42L, "BINANCE_US");
    }

    private static long startOf(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
