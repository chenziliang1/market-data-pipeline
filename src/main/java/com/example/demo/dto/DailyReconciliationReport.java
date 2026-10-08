package com.example.demo.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of comparing stored daily aggregates with the exchange's own daily candles.
 * {@code discrepancies} lists every day that did not match; matching days are only counted.
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
        List<DayDiscrepancy> discrepancies) {

    public enum Status {
        /** The day has all 1,440 minutes stored, but at least one field differs. */
        MISMATCH,
        /** Fewer than 1,440 minute candles are stored, so the day is not compared. */
        INCOMPLETE,
        /** The exchange returned no daily candle for the day. */
        MISSING_ON_EXCHANGE
    }

    public record DayDiscrepancy(
            LocalDate date,
            Status status,
            long storedCandleCount,
            List<FieldDifference> differences) {
    }

    public record FieldDifference(
            String field,
            String stored,
            String exchange) {
    }
}
