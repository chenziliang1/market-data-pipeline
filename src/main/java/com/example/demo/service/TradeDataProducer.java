package com.example.demo.service;

import com.example.demo.entity.TradeData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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

    public void send(TradeData tradeData) {
        String key = tradeData.getSymbol() + ":" + tradeData.getOpenTime();
        kafkaTemplate.send(tradeDataTopic, key, tradeData);
    }
}