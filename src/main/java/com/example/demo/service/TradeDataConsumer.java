package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.example.demo.mapper.TradeDataMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class TradeDataConsumer {

    private final TradeDataMapper tradeDataMapper;
    private final AggregateCacheVersions cacheVersions;

    public TradeDataConsumer(TradeDataMapper tradeDataMapper, AggregateCacheVersions cacheVersions) {
        this.tradeDataMapper = tradeDataMapper;
        this.cacheVersions = cacheVersions;
    }

    @KafkaListener(topics = "${app.kafka.topics.trade-data}")
    public void consume(TradeData tradeData) {
        int changedRows = tradeDataMapper.upsertTradeData(tradeData);
        if (changedRows > 0) {
            cacheVersions.bump(tradeData.getSymbol());
        }
    }
}
