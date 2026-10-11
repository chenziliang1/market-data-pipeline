package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisGuardTest {

    private static final DailyOhlcvResponse DAY = new DailyOhlcvResponse(
            "BTCUSDT",
            LocalDate.of(2024, 1, 2),
            "UTC",
            1704153600000L,
            1704239999999L,
            new BigDecimal("43998.83000000"),
            new BigDecimal("45889.62000000"),
            new BigDecimal("43961.25000000"),
            new BigDecimal("44953.74000000"),
            new BigDecimal("399.57179000"),
            54282L,
            "DATABASE");

    private static final AnalysisGuard.Metrics METRICS = AnalysisGuard.metrics(DAY);

    @Test
    void metricsAreComputedInCode() {
        assertThat(METRICS.range()).isEqualByComparingTo("1928.37");
        assertThat(METRICS.rangePct()).isEqualByComparingTo("4.38");
        assertThat(METRICS.change()).isEqualByComparingTo("954.91");
        assertThat(METRICS.changePct()).isEqualByComparingTo("2.17");
    }

    @Test
    void analysisThatOnlyCitesSuppliedFiguresPasses() {
        String analysis = """
                Data: BTCUSDT daily candle for January 2, 2024 (UTC), source DATABASE.
                It opened at 43,998.83, reached a high of 45889.62 and a low of 43,961.25, and closed at 44,953.7.
                The range was about 1,928 USDT, roughly 4.4% of the open; the close was about 954.91 USDT
                above the open, a gain of 2.17%. Volume was about 399.57 BTC across 54,282 trades.
                This is not investment advice.
                """;

        assertThat(AnalysisGuard.check(DAY, METRICS, analysis)).isEmpty();
    }

    @Test
    void wrongPriceAndInventedPercentageAreFlagged() {
        String analysis = """
                BTCUSDT on 2 January 2024 (UTC), source DATABASE.
                It opened at 43,998.83 and closed at 44,955.00, finishing at 51% of the day's range.
                This is not investment advice.
                """;

        List<String> problems = AnalysisGuard.check(DAY, METRICS, analysis);

        assertThat(problems).containsExactlyInAnyOrder(
                "cites a number the application did not supply: 44,955.00",
                "cites a number the application did not supply: 51%");
    }

    @Test
    void missingDateTimezoneSourceAndDisclaimerAreFlagged() {
        List<String> problems = AnalysisGuard.check(DAY, METRICS, "The day closed slightly higher.");

        assertThat(problems).containsExactlyInAnyOrder(
                "the date 2024-01-02 is not stated",
                "the UTC timezone is not stated",
                "the data source DATABASE is not stated",
                "the \"not investment advice\" reminder is missing");
    }

    @Test
    void overlongAnalysisIsFlagged() {
        // The fixed phrase is 6 words, so 195 more make 201.
        String analysis = "2024-01-02 UTC DATABASE not investment advice. " + "up ".repeat(195);

        assertThat(AnalysisGuard.check(DAY, METRICS, analysis))
                .containsExactly("longer than 200 words (201)");
    }
}
