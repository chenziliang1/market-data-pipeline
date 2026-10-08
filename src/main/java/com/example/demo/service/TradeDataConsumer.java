package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.example.demo.mapper.TradeDataMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.log.LogAccessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Writes each Kafka poll with one multi-row upsert instead of one statement per candle.
 *
 * <p>When a single record is bad, the records before it are written and
 * {@link BatchListenerFailedException} names the bad one, so the error handler sends only that
 * record to the dead-letter topic and redelivers the rest.
 */
@Service
public class TradeDataConsumer {

    private static final LogAccessor LOG = new LogAccessor(TradeDataConsumer.class);

    private final TradeDataMapper tradeDataMapper;
    private final AggregateCacheVersions cacheVersions;

    public TradeDataConsumer(TradeDataMapper tradeDataMapper, AggregateCacheVersions cacheVersions) {
        this.tradeDataMapper = tradeDataMapper;
        this.cacheVersions = cacheVersions;
    }

    @KafkaListener(topics = "${app.kafka.topics.trade-data}", batch = "true")
    public void consume(List<ConsumerRecord<String, TradeData>> records) {
        List<TradeData> pending = new ArrayList<>();
        int pendingStart = 0;

        for (int index = 0; index < records.size(); index++) {
            ConsumerRecord<String, TradeData> record = records.get(index);
            if (record.value() == null) {
                // ErrorHandlingDeserializer leaves the value null and puts the cause in a header.
                write(pending, pendingStart);
                throw new BatchListenerFailedException(
                        "Record could not be deserialized", deserializationFailure(record), index);
            }
            if (pending.isEmpty()) {
                pendingStart = index;
            }
            pending.add(record.value());
        }

        write(pending, pendingStart);
    }

    private void write(List<TradeData> pending, int startIndex) {
        if (pending.isEmpty()) {
            return;
        }
        try {
            bumpVersions(tradeDataMapper.upsertTradeDataBatch(latestVersionOfEachCandle(pending)));
        } catch (DataIntegrityViolationException batchFailure) {
            // One bad row fails the whole statement. Retry row by row to find it,
            // so only that record is dead-lettered and the good ones are kept.
            for (int offset = 0; offset < pending.size(); offset++) {
                TradeData candle = pending.get(offset);
                try {
                    if (tradeDataMapper.upsertTradeData(candle) > 0) {
                        cacheVersions.bump(candle.getSymbol());
                    }
                } catch (DataIntegrityViolationException rowFailure) {
                    throw new BatchListenerFailedException(
                            "Candle rejected by the database", rowFailure, startIndex + offset);
                }
            }
        }
        pending.clear();
    }

    /**
     * A poll can hold the same candle twice, for example a replay followed by a correction.
     * Keeps the last one, which is the newest because records of a symbol arrive in order.
     */
    private static List<TradeData> latestVersionOfEachCandle(List<TradeData> candles) {
        Map<String, TradeData> latest = new LinkedHashMap<>();
        for (TradeData candle : candles) {
            String key = candle.getSymbol() + ":" + candle.getOpenTime();
            latest.remove(key);
            latest.put(key, candle);
        }
        return new ArrayList<>(latest.values());
    }

    private void bumpVersions(List<String> changedSymbols) {
        new LinkedHashSet<>(changedSymbols).forEach(cacheVersions::bump);
    }

    private static Exception deserializationFailure(ConsumerRecord<String, TradeData> record) {
        DeserializationException failure = SerializationUtils.getExceptionFromHeader(
                record, SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOG);
        return failure != null ? failure : new IllegalStateException("Record has no value");
    }
}
