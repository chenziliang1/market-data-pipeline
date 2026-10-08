package com.example.demo.service;

import com.example.demo.entity.TradeData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class TradeDataProducer {

    private final KafkaTemplate<String, TradeData> kafkaTemplate;
    private final String tradeDataTopic;

    public TradeDataProducer(
            KafkaTemplate<String, TradeData> kafkaTemplate,
            @Value("${app.kafka.topics.trade-data}") String tradeDataTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.tradeDataTopic = tradeDataTopic;
    }

    /**
     * Keyed by symbol, so every version of a symbol's candles lands in the same partition
     * and a later correction is consumed after the version it replaces.
     */
    public CompletableFuture<SendResult<String, TradeData>> send(TradeData tradeData) {
        return kafkaTemplate.send(tradeDataTopic, tradeData.getSymbol(), tradeData);
    }
}
