package com.example.demo.service;

/**
 * Thrown when some Binance batches could not be fetched or published.
 * Candles from the other batches may already be in Kafka.
 */
public class MarketDataLoadException extends RuntimeException {

    private final int sentRecords;
    private final int failedBatches;
    private final int totalBatches;

    public MarketDataLoadException(int sentRecords, int failedBatches, int totalBatches) {
        super(String.format("%d records were sent to Kafka; %d of %d batches failed",
                sentRecords, failedBatches, totalBatches));
        this.sentRecords = sentRecords;
        this.failedBatches = failedBatches;
        this.totalBatches = totalBatches;
    }

    public int getSentRecords() {
        return sentRecords;
    }

    public int getFailedBatches() {
        return failedBatches;
    }

    public int getTotalBatches() {
        return totalBatches;
    }
}
