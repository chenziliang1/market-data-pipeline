package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.example.demo.mapper.TradeDataMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class TradeDataConsumer {

    private final TradeDataMapper tradeDataMapper;

    public TradeDataConsumer(TradeDataMapper tradeDataMapper) {
        this.tradeDataMapper = tradeDataMapper;
    }

    @KafkaListener(topics = "${app.kafka.topics.trade-data}")
    public void consume(TradeData tradeData) {
        tradeDataMapper.insertTradeData(tradeData);
    }
}