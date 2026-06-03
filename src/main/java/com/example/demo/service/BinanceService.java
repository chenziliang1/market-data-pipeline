package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.example.demo.mapper.TradeDataMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

@Service
public class BinanceService {

    @Autowired
    private TradeDataMapper tradeDataMapper; //注入 MyBatis的Mapper，用于后续将数据写入数据库

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper mapper;

    public int load(
            String symbol,
            Long startTime,
            Long endTime) {

        int maxPerRequest = 1000;
        long oneMinuteMs = 60000L; // 1 minute in milliseconds
        int totalRequired = (int)((endTime - startTime) / oneMinuteMs);

        // Calculate how many parallel API calls we need to make (1400 / 1000 = 2 requests)
        int numberOfRequests = (int) Math.ceil((double) totalRequired / maxPerRequest);


        // create an integer stream from 0 to numberOfRequests - 1
        int totalSaved = IntStream.range(0, numberOfRequests)
                .parallel() // convert normal stream to parallel stream, it can improve the efficiency
                .map(chunkIndex -> { // map each batch index(chuckindex)

                    return count(symbol, startTime, endTime, chunkIndex);
                })
                .sum(); // Combine the results of all parallel threads
        // 返回HTTP 200并且附带总共插入成功的记录数
        //            return ResponseEntity.ok(totalSaved + " records were inserted");
        return totalSaved;


    }

    private int count(String symbol,
                      Long startTime,
                      Long endTime,
                      int chunkIndex){
        int maxPerRequest = 1000;
        long oneMinuteMs = 60000L; // 1 minute in milliseconds
        int totalRequired = (int)((endTime - startTime) / oneMinuteMs);
        // Calculate how many parallel API calls we need to make (1400 / 1000 = 2 requests)
        int numberOfRequests = (int) Math.ceil((double) totalRequired / maxPerRequest);
        int limitForThisRequest = (chunkIndex == numberOfRequests - 1)
                ? totalRequired - (chunkIndex * maxPerRequest)
                : maxPerRequest;

        // Pre-calculate the startTime for this specific API call
        long batchStartTime = startTime + (chunkIndex * maxPerRequest * oneMinuteMs);

        String feeResourceUrl = "https://www.binance.us/api/v3/klines?symbol=" + symbol +
                "&startTime=" + batchStartTime + "&endTime=" + endTime +
                "&interval=1m&limit=" + limitForThisRequest;

        // restTemplate:访问REST服务的客户端工具; getForEntity:获取完整HTTP实体的请求
        ResponseEntity<String> response = restTemplate.getForEntity(feeResourceUrl, String.class);

        try {
            JsonNode rootNode = mapper.readTree(response.getBody());

            if (rootNode != null && rootNode.isArray() && !rootNode.isEmpty()) {
                // 3. PARALLEL PARSE: Map JSON to objects inside each thread
                List<TradeData> batchData = StreamSupport.stream(rootNode.spliterator(), false)//把json数组转换成java stream,并且开启串行流（单线程）
                        .map(klineNode -> mapToTradeData(klineNode, symbol, batchStartTime, endTime))//把json数组转换成TradeData
                        .collect(Collectors.toList());//打包成list

                // 4. SAVE: Insert into the database
                batchData.forEach(tradeDataMapper::insertTradeData);

                return batchData.size(); // Return records saved in this thread
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0; // Return 0 if something failed in this thread
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