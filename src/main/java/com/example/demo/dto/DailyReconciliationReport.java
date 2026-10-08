package com.example.demo.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of comparing stored daily aggregates with the exchange's own daily candles, and the
 * served daily rollups with the minute candles they summarize.
 * {@code discrepancies} lists every problem found; matching days are only counted.
 */
public record DailyReconciliationReport(
        String symbol,
        LocalDate from,
        LocalDate to,
        int checkedDays,
        int matchedDays,
        int mismatchedDays,
        int incompleteDays,
        int missingOnExchangeDays,
        int rollupMismatchedDays,
        List<DayDiscrepancy> discrepancies) {

    public enum Status {
        /** The day has all 1,440 minutes stored, but at least one field differs. */
        MISMATCH,
        /** Fewer than 1,440 minute candles are stored, so the day is not compared. */
        INCOMPLETE,
        /** The exchange returned no daily candle for the day. */
        MISSING_ON_EXCHANGE,
        /** The daily rollup that queries are served from differs from its minute candles. */
        ROLLUP_MISMATCH
    }

    public record DayDiscrepancy(
            LocalDate date,
            Status status,
            long storedCandleCount,
            List<FieldDifference> differences) {
    }

    /**
     * For {@link Status#MISMATCH}, {@code expected} is the exchange's value; for
     * {@link Status#ROLLUP_MISMATCH}, it is the value recomputed from the minute candles.
     */
    public record FieldDifference(
            String field,
            String stored,
            String expected) {
    }
}
