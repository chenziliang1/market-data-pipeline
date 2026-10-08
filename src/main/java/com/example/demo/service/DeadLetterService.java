package com.example.demo.service;

import com.example.demo.dto.DeadLetterReport.DeadLetter;
import com.example.demo.dto.DeadLetterReport.ReplayResult;
import com.example.demo.dto.DeadLetterReport.Skipped;
import com.example.demo.entity.TradeData;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lists and replays records from the dead-letter topic.
 *
 * <p>Progress is tracked with a consumer group of its own: everything before its committed offset
 * has been either republished or skipped. A replayed record carries a replay count, so a record
 * that keeps failing stops coming back after {@code app.kafka.dlt.max-replays} attempts.
 */
@Service
public class DeadLetterService {

    static final String REPLAY_COUNT_HEADER = "x-replay-count";
    private static final int MAX_VALUE_PREVIEW = 500;
    private static final Duration READ_DEADLINE = Duration.ofSeconds(10);

    private final ConsumerFactory<?, ?> consumerFactory;
    private final KafkaTemplate<String, TradeData> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String deadLetterTopic;
    private final String tradeDataTopic;
    private final String replayGroupId;
    private final int maxReplays;

    public DeadLetterService(
            ConsumerFactory<?, ?> consumerFactory,
            KafkaTemplate<String, TradeData> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${app.kafka.topics.trade-data-dlt}") String deadLetterTopic,
            @Value("${app.kafka.topics.trade-data}") String tradeDataTopic,
            @Value("${spring.kafka.consumer.group-id}") String consumerGroupId,
            @Value("${app.kafka.dlt.max-replays:3}") int maxReplays) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.deadLetterTopic = deadLetterTopic;
        this.tradeDataTopic = tradeDataTopic;
        this.replayGroupId = consumerGroupId + "-dlt-replay";
        this.maxReplays = maxReplays;
    }

    /** Lists up to {@code limit} records that have not been replayed or skipped yet, oldest first. */
    public synchronized List<DeadLetter> pending(int limit) {
        try (KafkaConsumer<String, byte[]> consumer = openAtReplayPosition()) {
            return readPending(consumer, limit).stream().map(this::describe).toList();
        }
    }

    /**
     * Republishes up to {@code limit} pending records to the trade-data topic, waits until Kafka has
     * acknowledged them, then commits the replay position past every record it handled.
     */
    public synchronized ReplayResult replay(int limit) {
        try (KafkaConsumer<String, byte[]> consumer = openAtReplayPosition()) {
            List<ConsumerRecord<String, byte[]>> records = readPending(consumer, limit);
            List<CompletableFuture<SendResult<String, TradeData>>> sends = new ArrayList<>();
            List<Skipped> skipped = new ArrayList<>();
            Map<TopicPartition, OffsetAndMetadata> handled = new HashMap<>();

            for (ConsumerRecord<String, byte[]> record : records) {
                Parsed parsed = parse(record);
                if (parsed.candle() == null) {
                    skipped.add(new Skipped(record.partition(), record.offset(), record.key(), parsed.reason()));
                } else {
                    sends.add(kafkaTemplate.send(republished(record, parsed.candle())));
                }
                handled.put(new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1));
            }

            // Commit only after every republished record is safely in Kafka; if a send fails,
            // nothing is committed and the same records are offered again next time.
            awaitAll(sends);
            if (!handled.isEmpty()) {
                consumer.commitSync(handled);
            }
            return new ReplayResult(sends.size(), skipped);
        }
    }

    private KafkaConsumer<String, byte[]> openAtReplayPosition() {
        Map<String, Object> properties = new HashMap<>(consumerFactory.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, replayGroupId);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties);

        List<TopicPartition> partitions = consumer.partitionsFor(deadLetterTopic, READ_DEADLINE).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        consumer.assign(partitions);
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(partitions));
        Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata position = committed.get(partition);
            // Records may have expired since the last replay, so never seek before the first one left.
            consumer.seek(partition, position == null
                    ? beginning.get(partition)
                    : Math.max(position.offset(), beginning.get(partition)));
        }
        return consumer;
    }

    private List<ConsumerRecord<String, byte[]>> readPending(KafkaConsumer<String, byte[]> consumer, int limit) {
        Map<TopicPartition, Long> end = consumer.endOffsets(consumer.assignment());
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        long deadline = System.nanoTime() + READ_DEADLINE.toNanos();

        while (records.size() < limit && !reachedEnd(consumer, end) && System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                if (records.size() < limit) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    private static boolean reachedEnd(KafkaConsumer<?, ?> consumer, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private DeadLetter describe(ConsumerRecord<String, byte[]> record) {
        Parsed parsed = parse(record);
        Headers headers = record.headers();
        String value = record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8);
        if (value != null && value.length() > MAX_VALUE_PREVIEW) {
            value = value.substring(0, MAX_VALUE_PREVIEW);
        }
        return new DeadLetter(
                record.partition(),
                record.offset(),
                record.key(),
                record.timestamp(),
                text(headers, KafkaHeaders.DLT_EXCEPTION_FQCN),
                text(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                text(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                longValue(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                replayCount(record),
                parsed.candle() != null,
                parsed.reason(),
                value);
    }

    /** A record is replayable when it is a complete candle and has not been replayed too often. */
    private Parsed parse(ConsumerRecord<String, byte[]> record) {
        int replays = replayCount(record);
        if (replays >= maxReplays) {
            return new Parsed(null, "already replayed " + replays + " times");
        }
        if (record.value() == null) {
            return new Parsed(null, "record has no value");
        }
        TradeData candle;
        try {
            candle = objectMapper.readValue(record.value(), TradeData.class);
        } catch (JacksonException e) {
            return new Parsed(null, "value is not a candle: " + e.getOriginalMessage());
        } catch (IOException e) {
            return new Parsed(null, "value is not a candle: " + e.getMessage());
        }
        if (candle == null || candle.getSymbol() == null || candle.getOpenTime() == null) {
            return new Parsed(null, "candle has no symbol or open time");
        }
        return new Parsed(candle, null);
    }

    /**
     * Keeps the key, so the record lands in the same partition as the rest of its symbol. The
     * dead-letter headers are dropped; if it fails again, the error handler adds fresh ones.
     */
    private ProducerRecord<String, TradeData> republished(ConsumerRecord<String, byte[]> record, TradeData candle) {
        Headers headers = new RecordHeaders();
        headers.add(REPLAY_COUNT_HEADER,
                String.valueOf(replayCount(record) + 1).getBytes(StandardCharsets.UTF_8));
        String key = record.key() != null ? record.key() : candle.getSymbol();
        return new ProducerRecord<>(tradeDataTopic, null, key, candle, headers);
    }

    private static int replayCount(ConsumerRecord<?, ?> record) {
        String count = text(record.headers(), REPLAY_COUNT_HEADER);
        try {
            return count == null ? 0 : Integer.parseInt(count.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Long longValue(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value().length != Long.BYTES ? null : ByteBuffer.wrap(header.value()).getLong();
    }

    private static void awaitAll(List<CompletableFuture<SendResult<String, TradeData>>> sends) {
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while republishing dead letters", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Republishing dead letters failed; nothing was committed", e);
        }
    }

    private record Parsed(TradeData candle, String reason) {
    }
}
