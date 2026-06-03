package com.example.demo.controller;

import com.example.demo.service.BinanceService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
public class LoadControllerTest {

    @Mock
    private BinanceService binanceService;

    @InjectMocks
    private LoadController controller;

    @Test
    public void test_load() {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = 1697068502000L;

        Mockito.when(binanceService.load(symbol, startTime, endTime))
                .thenReturn(2);

        ResponseEntity<String> response =
                controller.load(symbol, startTime, endTime);

        Assertions.assertEquals(200, response.getStatusCode().value());
        Assertions.assertEquals("2 records were inserted", response.getBody());

        Mockito.verify(binanceService).load(symbol, startTime, endTime);
    }
}