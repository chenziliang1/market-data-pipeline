package com.example.demo.service;

import com.example.demo.entity.TradeData;
import com.example.demo.mapper.TradeDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BinanceServiceTest {

    @Mock
    private TradeDataMapper tradeDataMapper;

    @Mock
    private RestTemplate restTemplate;

    @Spy
    private ObjectMapper mapper = new ObjectMapper();

    @InjectMocks
    private BinanceService binanceService;

    @Test
    public void test_load_success_insertTwoRecords() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = startTime + 2 * 60000L;

        String json = """
                [
                  [
                    1697068382000,
                    "100.10",
                    "105.20",
                    "99.50",
                    "102.30",
                    "12.50",
                    1697068439999,
                    "0",
                    30
                  ],
                  [
                    1697068442000,
                    "102.30",
                    "108.00",
                    "101.00",
                    "107.50",
                    "20.00",
                    1697068499999,
                    "0",
                    45
                  ]
                ]
                """;

        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(json));

        int result = binanceService.load(symbol, startTime, endTime);

        Assertions.assertEquals(2, result);

        ArgumentCaptor<TradeData> captor =
                ArgumentCaptor.forClass(TradeData.class);

        verify(tradeDataMapper, times(2)).insertTradeData(captor.capture());

        List<TradeData> savedData = captor.getAllValues();
        TradeData first = savedData.get(0);

        Assertions.assertEquals(1697068382000L, first.getOpenTime());
        Assertions.assertEquals(new BigDecimal("100.10"), first.getOpenPrice());
        Assertions.assertEquals(new BigDecimal("105.20"), first.getHighPrice());
        Assertions.assertEquals(new BigDecimal("99.50"), first.getLowPrice());
        Assertions.assertEquals(new BigDecimal("102.30"), first.getClosePrice());
        Assertions.assertEquals(new BigDecimal("12.50"), first.getVolume());
        Assertions.assertEquals(1697068439999L, first.getCloseTime());
        Assertions.assertEquals(30L, first.getNumsOfTrade());
        Assertions.assertEquals("BTCUSDT", first.getSymbol());

        verify(restTemplate).getForEntity(
                contains("symbol=BTCUSDT"),
                eq(String.class)
        );
    }

    @Test
    public void test_load_emptyApiResponse_returnZero() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = startTime + 60000L;

        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("[]"));

        int result = binanceService.load(symbol, startTime, endTime);

        Assertions.assertEquals(0, result);
        verify(tradeDataMapper, never()).insertTradeData(any());
    }

    @Test
    public void test_load_invalidJson_returnZero() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = startTime + 60000L;

        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("invalid json"));

        int result = binanceService.load(symbol, startTime, endTime);

        Assertions.assertEquals(0, result);
        verify(tradeDataMapper, never()).insertTradeData(any());
    }
}