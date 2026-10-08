package com.example.demo.service;

import com.example.demo.aop.TrackExecutionTime;
import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Service
public class AggregationService {

    private static final Logger logger = LoggerFactory.getLogger(AggregationService.class);
    private static final long ONE_MINUTE_MS = 60_000L;

    private final AggregateQuery aggregateQuery;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AggregateCacheVersions cacheVersions;
    private final Clock clock;
    private final Duration closedRangeTtl;
    private final Duration openRangeTtl;
    private final JavaType resultType;

    public AggregationService(
            AggregateQuery aggregateQuery,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            AggregateCacheVersions cacheVersions,
            Clock clock,
            @Value("${app.cache.aggregate.closed-ttl:PT1H}") Duration closedRangeTtl,
            @Value("${app.cache.aggregate.open-ttl:PT1M}") Duration openRangeTtl) {
        this.aggregateQuery = aggregateQuery;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cacheVersions = cacheVersions;
        this.clock = clock;
        this.closedRangeTtl = closedRangeTtl;
        this.openRangeTtl = openRangeTtl;
        this.resultType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, AggregatedTradeData.class);
    }

    @TrackExecutionTime
    public List<AggregatedTradeData> getAggregated(
            String symbol,
            long startTime,
            long endTime,
            AggregationPeriod period)
            throws JsonProcessingException {

        if (startTime >= endTime) {
            throw new IllegalArgumentException(
                    "startTime must be earlier than endTime");
        }

        symbol = symbol.toUpperCase(Locale.ROOT);

        // The symbol's version is part of the key: ingesting a new or corrected candle bumps it,
        // so results computed before that write are never read again.
        String version = cacheVersions.current(symbol);
        String key = version == null ? null
                : "aggregate:v2:"
                + period.name().toLowerCase(Locale.ROOT) + ":"
                + symbol + ":" + version + ":" + startTime + ":" + endTime;

        if (key != null) {
            String cached = readCache(key);
            if (cached != null) {
                logger.debug("Aggregate cache hit: {}", key);
                return objectMapper.readValue(cached, resultType);
            }
            logger.debug("Aggregate cache miss: {}", key);
        }

        long now = clock.millis();
        List<AggregatedTradeData> result = aggregateQuery.aggregate(symbol, startTime, endTime, period);
        result.forEach(row -> annotateCoverage(row, startTime, endTime, period, now));

        if (key != null) {
            // A range reaching past "now" still covers candles that have not closed or arrived yet.
            Duration ttl = endTime > now ? openRangeTtl : closedRangeTtl;
            writeCache(key, objectMapper.writeValueAsString(result), ttl);
        }

        return result;
    }

    private void annotateCoverage(
            AggregatedTradeData row, long startTime, long endTime, AggregationPeriod period, long now) {
        long windowStart = Math.max(row.getBucketStartTime(), startTime);
        long windowEnd = Math.min(row.getBucketStartTime() + period.getBucketMillis(), endTime);
        long expected = ceilDiv(windowEnd, ONE_MINUTE_MS) - ceilDiv(windowStart, ONE_MINUTE_MS);

        row.setExpectedCandleCount(expected);
        row.setComplete(row.getCandleCount() != null
                && row.getCandleCount() == expected
                && windowEnd <= now);
    }

    private String readCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            logger.warn("Redis read failed, querying PostgreSQL directly: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException e) {
            logger.warn("Redis write failed, result not cached: {}", e.getMessage());
        }
    }

    private static long ceilDiv(long dividend, long divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }
}
