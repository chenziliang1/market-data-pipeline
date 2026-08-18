package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.ParsedMarketQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KimiServicesTest {

    private static final String API_URL =
            "https://api.moonshot.ai/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void intentExtractionLimitsOutputAndParsesStructuredResponse()
            throws Exception {

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        String structuredContent = objectMapper.writeValueAsString(
                Map.of(
                        "symbol", "BTCUSDT",
                        "date", "2024-01-02",
                        "error", ""));

        String response = chatResponse(structuredContent);

        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andExpect(jsonPath("$.max_completion_tokens").value(512))
                .andExpect(jsonPath("$.response_format.type")
                        .value("json_schema"))
                .andRespond(withSuccess(
                        response,
                        MediaType.APPLICATION_JSON));

        KimiIntentExtractionService service =
                new KimiIntentExtractionService(
                        restTemplate,
                        objectMapper,
                        API_URL,
                        "test-key",
                        "kimi-k3");

        ParsedMarketQuery result = service.extract(
                "查询 2024 年 1 月 2 日的比特币行情");

        assertEquals("BTCUSDT", result.symbol());
        assertEquals(LocalDate.of(2024, 1, 2), result.date());
        server.verify();
    }

    @Test
    void analysisLimitsOutputAndReturnsContent()
            throws Exception {

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                .andExpect(jsonPath("$.max_completion_tokens").value(1024))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(
                        chatResponse("行情分析结果"),
                        MediaType.APPLICATION_JSON));

        KimiAnalysisService service =
                new KimiAnalysisService(
                        restTemplate,
                        API_URL,
                        "test-key",
                        "kimi-k3");

        DailyOhlcvResponse marketData =
                new DailyOhlcvResponse(
                        "BTCUSDT",
                        LocalDate.of(2024, 1, 2),
                        "UTC",
                        1L,
                        2L,
                        new BigDecimal("100"),
                        new BigDecimal("110"),
                        new BigDecimal("90"),
                        new BigDecimal("105"),
                        new BigDecimal("12.5"),
                        42L,
                        "DATABASE");

        String result = service.analyze(
                marketData,
                "请分析当天行情");

        assertEquals("行情分析结果", result);
        server.verify();
    }

    private String chatResponse(String content)
            throws Exception {

        return objectMapper.writeValueAsString(
                Map.of(
                        "choices",
                        List.of(
                                Map.of(
                                        "message",
                                        Map.of(
                                                "role", "assistant",
                                                "content", content)))));
    }
}
