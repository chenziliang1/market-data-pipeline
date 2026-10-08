package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A per-symbol version number that is part of every aggregate cache key.
 * Bumping it when a candle is inserted or corrected makes all cached aggregates
 * for that symbol unreachable at once; the old entries simply expire.
 */
@Component
public class AggregateCacheVersions {

    private static final Logger logger = LoggerFactory.getLogger(AggregateCacheVersions.class);
    private static final String KEY_PREFIX = "aggregate:version:";

    private final StringRedisTemplate redis;

    public AggregateCacheVersions(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return the current version, or {@code null} if Redis is unavailable and caching should be skipped
     */
    public String current(String symbol) {
        try {
            String version = redis.opsForValue().get(KEY_PREFIX + symbol);
            return version == null ? "0" : version;
        } catch (DataAccessException e) {
            logger.warn("Redis unavailable, skipping aggregate cache for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public void bump(String symbol) {
        try {
            redis.opsForValue().increment(KEY_PREFIX + symbol);
        } catch (DataAccessException e) {
            // The row is already written; cached aggregates stay stale until their TTL expires.
            logger.warn("Could not invalidate cached aggregates for {}: {}", symbol, e.getMessage());
        }
    }
}
