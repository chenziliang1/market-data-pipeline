package com.example.demo.dto;

import java.time.LocalDate;

public record ParsedMarketQuery(
        String symbol,
        LocalDate date) {
}