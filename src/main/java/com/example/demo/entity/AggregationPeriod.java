package com.example.demo.entity;

public enum AggregationPeriod {

    HOURLY("hour", 3_600_000L),
    DAILY("day", 86_400_000L);

    private final String postgresValue;
    private final long bucketMillis;

    AggregationPeriod(String postgresValue, long bucketMillis) {
        this.postgresValue = postgresValue;
        this.bucketMillis = bucketMillis;
    }

    public String getPostgresValue() {
        return postgresValue;
    }

    /** Buckets are UTC hours or UTC days, which always have a fixed length. */
    public long getBucketMillis() {
        return bucketMillis;
    }
}
