package com.example.demo.service;

import com.example.demo.entity.AggregationPeriod;
import com.example.demo.entity.TradeData;
import com.example.demo.mapper.AggregationMapper;
import com.example.demo.mapper.TradeDataMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes minute candles and refreshes the hourly and daily rollups they belong to in the same
 * transaction. If the rollups were updated separately, a crash between the two writes would leave
 * them stale for good: the redelivered candles would be identical, so nothing would trigger
 * another refresh.
 */
@Service
public class CandleWriter {

    private final TradeDataMapper tradeDataMapper;
    private final AggregationMapper aggregationMapper;

    public CandleWriter(TradeDataMapper tradeDataMapper, AggregationMapper aggregationMapper) {
        this.tradeDataMapper = tradeDataMapper;
        this.aggregationMapper = aggregationMapper;
    }

    /**
     * Upserts candles that are unique by (symbol, open time).
     *
     * @return the symbols whose stored data changed
     */
    @Transactional
    public Set<String> writeBatch(List<TradeData> candles) {
        lockSymbols(candles);
        return refreshRollups(tradeDataMapper.upsertTradeDataBatch(candles));
    }

    /** @return the symbol if the candle was inserted or changed, otherwise an empty set */
    @Transactional
    public Set<String> writeOne(TradeData candle) {
        lockSymbols(List.of(candle));
        return tradeDataMapper.upsertTradeData(candle) > 0 ? refreshRollups(List.of(candle)) : Set.of();
    }

    /** Locks in a fixed order, so two transactions holding several symbols cannot deadlock. */
    private void lockSymbols(List<TradeData> candles) {
        Set<String> symbols = new TreeSet<>();
        candles.forEach(candle -> {
            if (candle.getSymbol() != null) {
                symbols.add(candle.getSymbol());
            }
        });
        symbols.forEach(tradeDataMapper::lockSymbol);
    }

    private Set<String> refreshRollups(List<TradeData> changed) {
        Map<String, List<Long>> openTimesBySymbol = new TreeMap<>();
        for (TradeData candle : changed) {
            if (candle.getSymbol() == null) {
                continue; // cannot belong to any rollup
            }
            openTimesBySymbol.computeIfAbsent(candle.getSymbol(), symbol -> new ArrayList<>())
                    .add(candle.getOpenTime());
        }
        openTimesBySymbol.forEach((symbol, openTimes) -> {
            for (AggregationPeriod period : AggregationPeriod.values()) {
                long size = period.getBucketMillis();
                for (long[] run : contiguousBucketRuns(openTimes, size)) {
                    aggregationMapper.refreshRollups(symbol, period.getPostgresValue(), size, run[0], run[1]);
                }
            }
        });
        return openTimesBySymbol.keySet();
    }

    /**
     * Groups the buckets touched by these candles into runs of adjacent buckets, so a backfill of
     * consecutive minutes is one statement per period while a correction far from the rest does not
     * make the refresh scan everything in between.
     */
    static List<long[]> contiguousBucketRuns(List<Long> openTimes, long bucketMillis) {
        TreeSet<Long> buckets = new TreeSet<>();
        openTimes.forEach(openTime -> buckets.add(openTime - Math.floorMod(openTime, bucketMillis)));

        List<long[]> runs = new ArrayList<>();
        long runStart = -1;
        long runEnd = -1;
        for (long bucket : buckets) {
            if (runEnd == bucket) {
                runEnd = bucket + bucketMillis;
            } else {
                if (runStart >= 0) {
                    runs.add(new long[]{runStart, runEnd});
                }
                runStart = bucket;
                runEnd = bucket + bucketMillis;
            }
        }
        if (runStart >= 0) {
            runs.add(new long[]{runStart, runEnd});
        }
        return runs;
    }
}
