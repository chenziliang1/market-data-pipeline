package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

@Service
public class BinanceService {

    private static final Logger logger = LoggerFactory.getLogger(BinanceService.class);

    private final TradeDataProducer tradeDataProducer;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public BinanceService(
            TradeDataProducer tradeDataProducer,
            RestTemplate restTemplate,
            ObjectMapper mapper) {
        this.tradeDataProducer = tradeDataProducer;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    public int load(String symbol, Long startTime, Long endTime) {
        int maxPerRequest = 1000;
        long oneMinuteMs = 60000L;
        int totalRequired = (int) ((endTime - startTime) / oneMinuteMs);
        int numberOfRequests = (int) Math.ceil((double) totalRequired / maxPerRequest);

        return IntStream.range(0, numberOfRequests)
                .parallel()
                .map(chunkIndex -> count(symbol, startTime, endTime, chunkIndex))
                .sum();
    }

    private int count(String symbol, Long startTime, Long endTime, int chunkIndex) {
        int maxPerRequest = 1000;
        long oneMinuteMs = 60000L;
        int totalRequired = (int) ((endTime - startTime) / oneMinuteMs);
        int numberOfRequests = (int) Math.ceil((double) totalRequired / maxPerRequest);

        int limitForThisRequest = (chunkIndex == numberOfRequests - 1)
                ? totalRequired - (chunkIndex * maxPerRequest)
                : maxPerRequest;

        long batchStartTime = startTime + (chunkIndex * maxPerRequest * oneMinuteMs);

        String feeResourceUrl = "https://www.binance.us/api/v3/klines?symbol=" + symbol
                + "&startTime=" + batchStartTime
                + "&endTime=" + endTime
                + "&interval=1m&limit=" + limitForThisRequest;

        ResponseEntity<String> response = restTemplate.getForEntity(feeResourceUrl, String.class);

        try {
            JsonNode rootNode = mapper.readTree(response.getBody());

            if (rootNode != null && rootNode.isArray() && !rootNode.isEmpty()) {
                List<TradeData> batchData = StreamSupport.stream(rootNode.spliterator(), false)
                        .map(klineNode -> mapToTradeData(klineNode, symbol, batchStartTime, endTime))
                        .collect(Collectors.toList());

                batchData.forEach(tradeDataProducer::send);

                return batchData.size();
            }
        } catch (Exception e) {
            String errorMessage = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage().split("\\R", 2)[0];
            logger.warn(
                    "Failed to parse Binance response for symbol {} from {} to {}: {}",
                    symbol,
                    batchStartTime,
                    endTime,
                    errorMessage
            );
        }

        return 0;
    }

    private TradeData mapToTradeData(JsonNode klineNode, String symbol, Long startTime, Long endTime) {
        TradeData data = new TradeData();
        data.setOpenTime(klineNode.get(0).asLong());
        data.setOpenPrice(new BigDecimal(klineNode.get(1).asText()));
        data.setHighPrice(new BigDecimal(klineNode.get(2).asText()));
        data.setLowPrice(new BigDecimal(klineNode.get(3).asText()));
        data.setClosePrice(new BigDecimal(klineNode.get(4).asText()));
        data.setVolume(new BigDecimal(klineNode.get(5).asText()));
        data.setCloseTime(klineNode.get(6).asLong());
        data.setNumsOfTrade(klineNode.get(8).asLong());
        data.setSymbol(symbol);
        data.setStartTime(startTime);
        data.setEndTime(endTime);
        return data;
    }
}
