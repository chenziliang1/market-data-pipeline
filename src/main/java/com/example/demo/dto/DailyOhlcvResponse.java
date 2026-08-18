package com.example.demo.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyOhlcvResponse(
        String symbol,
        LocalDate date,
        String timezone,
        long openTime,
        long closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        Long tradeCount,
        String source) {
}