package com.example.demo.entity;

public enum AggregationPeriod {

    HOURLY("hour"),
    DAILY("day");

    private final String postgresValue;

    AggregationPeriod(String postgresValue) {
        this.postgresValue = postgresValue;
    }

    public String getPostgresValue() {
        return postgresValue;
    }
}