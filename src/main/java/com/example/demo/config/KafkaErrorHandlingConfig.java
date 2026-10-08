package com.example.demo.config;

import com.example.demo.entity.TradeData;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retries a failing record with exponential backoff, then publishes it to a dead-letter topic
 * so one bad record cannot block the partition or disappear silently. Records that cannot be
 * deserialized are not retried (the default error handler treats that as fatal) and go straight
 * to the dead-letter topic with their original bytes.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            ProducerFactory<?, ?> producerFactory,
            @Value("${app.kafka.topics.trade-data-dlt}") String deadLetterTopic,
            @Value("${app.kafka.retry.max-retries:3}") int maxRetries,
            @Value("${app.kafka.retry.initial-interval:PT1S}") Duration initialInterval) {

        // Raw bytes for records that failed deserialization, JSON for records that failed processing.
        Map<Class<?>, Serializer<?>> valueSerializers = new LinkedHashMap<>();
        valueSerializers.put(byte[].class, new ByteArraySerializer());
        valueSerializers.put(TradeData.class, new JsonSerializer<TradeData>());

        KafkaTemplate<String, Object> deadLetterTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(
                        new HashMap<>(producerFactory.getConfigurationProperties()),
                        new StringSerializer(),
                        new DelegatingByTypeSerializer(valueSerializers)));

        // Partition -1 lets Kafka choose, so the dead-letter topic needs only one partition.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initialInterval.toMillis());
        backOff.setMultiplier(2.0);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
