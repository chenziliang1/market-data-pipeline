package com.example.demo.entity;

import java.math.BigDecimal;

public class AggregatedTradeData {
    public Long getBucketStartTime() {
        return bucketStartTime;
    }

    public void setBucketStartTime(Long bucketStartTime) {
        this.bucketStartTime = bucketStartTime;
    }

    public BigDecimal getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(BigDecimal openPrice) {
        this.openPrice = openPrice;
    }

    public BigDecimal getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(BigDecimal highPrice) {
        this.highPrice = highPrice;
    }

    public BigDecimal getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(BigDecimal lowPrice) {
        this.lowPrice = lowPrice;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public Long getNumsOfTrade() {
        return numsOfTrade;
    }

    public void setNumsOfTrade(Long numsOfTrade) {
        this.numsOfTrade = numsOfTrade;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    private Long bucketStartTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal volume;
    private Long numsOfTrade;
    private String symbol;
}