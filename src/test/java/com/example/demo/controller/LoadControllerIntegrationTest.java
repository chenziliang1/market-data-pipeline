package com.example.demo.controller;

import com.example.demo.service.BinanceService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
public class LoadControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BinanceService binanceService;

    @Test
    public void test_load() throws Exception {
        String symbol = "BTCUSDT";
        Long startTime = 1697068382000L;
        Long endTime = 1697068502000L;

        Mockito.when(binanceService.load(symbol, startTime, endTime))
                .thenReturn(2);

        mvc.perform(get("/BTCUSDT/1697068382000/1697068502000"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 records were sent to Kafka")));

        Mockito.verify(binanceService).load(symbol, startTime, endTime);
    }
}
