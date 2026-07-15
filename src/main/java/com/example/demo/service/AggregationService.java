package com.example.demo.service;

import com.example.demo.aop.TrackExecutionTime;
import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.example.demo.mapper.AggregationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Service
public class AggregationService {

    private final AggregationMapper aggregationMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AggregationService(
            AggregationMapper aggregationMapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.aggregationMapper = aggregationMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
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

        String key = "aggregate:v1:"
                + period.name().toLowerCase() + ":"
                + symbol + ":" + startTime + ":" + endTime;

        String cached = redis.opsForValue().get(key);

        if (cached != null) {
            System.out.println("Redis HIT: " + key);

            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(
                            List.class,
                            AggregatedTradeData.class);

            return objectMapper.readValue(cached, type);
        }

        System.out.println("Redis MISS: " + key);

        List<AggregatedTradeData> result =
                aggregationMapper.findAggregated(
                        symbol,
                        startTime,
                        endTime,
                        period.getPostgresValue());

        redis.opsForValue().set(
                key,
                objectMapper.writeValueAsString(result),
                Duration.ofHours(1));

        return result;
    }
}