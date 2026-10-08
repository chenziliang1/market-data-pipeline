package com.example.demo.service;

import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.example.demo.mapper.AggregationMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers an aggregate query from the rollup table for every whole bucket in the range, and from
 * the minute candles only for the partial buckets at either end.
 */
@Component
public class AggregateQuery {

    private final AggregationMapper aggregationMapper;

    public AggregateQuery(AggregationMapper aggregationMapper) {
        this.aggregationMapper = aggregationMapper;
    }

    public List<AggregatedTradeData> aggregate(
            String symbol, long startTime, long endTime, AggregationPeriod period) {
        long size = period.getBucketMillis();
        long firstWholeBucket = -Math.floorDiv(-startTime, size) * size;  // startTime rounded up
        long endOfWholeBuckets = Math.floorDiv(endTime, size) * size;    // endTime rounded down
        String postgresPeriod = period.getPostgresValue();

        if (firstWholeBucket >= endOfWholeBuckets) {
            return aggregationMapper.findAggregated(symbol, startTime, endTime, postgresPeriod);
        }

        List<AggregatedTradeData> result = new ArrayList<>();
        if (startTime < firstWholeBucket) {
            result.addAll(aggregationMapper.findAggregated(symbol, startTime, firstWholeBucket, postgresPeriod));
        }
        result.addAll(aggregationMapper.findRollups(symbol, firstWholeBucket, endOfWholeBuckets, postgresPeriod));
        if (endOfWholeBuckets < endTime) {
            result.addAll(aggregationMapper.findAggregated(symbol, endOfWholeBuckets, endTime, postgresPeriod));
        }
        return result;
    }
}
