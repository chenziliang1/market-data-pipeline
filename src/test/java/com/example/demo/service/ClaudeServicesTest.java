package com.example.demo.service;

import com.example.demo.config.ClaudeRestTemplateConfig;
import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.ParsedMarketQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClaudeServicesTest {

    private static final String API_URL =
            "https://api.anthropic.com/v1/messages";

    private static final String MODEL = "claude-sonnet-5-5";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void intentExtractionRequestsStructuredOutputAndParsesIt()
            throws Exception {

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        String structuredContent = objectMapper.writeValueAsString(
                Map.of(
                        "symbol", "BTCUSDT",
                        "date", "2024-01-02",
                        "error", ""));

        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.output_config.format.type")
                        .value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.required[0]")
                        .value("symbol"))
                .andExpect(jsonPath("$.output_config.format.schema.additionalProperties")
                        .value(false))
                .andRespond(withSuccess(
                        textResponse(structuredContent),
                        MediaType.APPLICATION_JSON));

        ClaudeIntentExtractionService service =
                new ClaudeIntentExtractionService(
                        restTemplate,
                        objectMapper,
                        API_URL,
                        "test-key",
                        MODEL);

        ParsedMarketQuery result = service.extract(
                "查询 2024 年 1 月 2 日的比特币行情");

        assertEquals("BTCUSDT", result.symbol());
        assertEquals(LocalDate.of(2024, 1, 2), result.date());
        server.verify();
    }

    @Test
    void intentExtractionRejectsAnAnswerThatIsNotJson()
            throws Exception {

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        textResponse("The date is 2024-01-02."),
                        MediaType.APPLICATION_JSON));

        ClaudeIntentExtractionService service =
                new ClaudeIntentExtractionService(
                        restTemplate,
                        objectMapper,
                        API_URL,
                        "test-key",
                        MODEL);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.extract("查询 2024 年 1 月 2 日的比特币行情"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        server.verify();
    }

    @Test
    void intentExtractionRejectsTodayEvenWhenTheModelAcceptsIt()
            throws Exception {

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        String today = LocalDate.now(java.time.ZoneOffset.UTC).toString();

        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        textResponse(objectMapper.writeValueAsString(
                                Map.of(
                                        "symbol", "BTCUSDT",
                                        "date", today,
                                        "error", ""))),
                        MediaType.APPLICATION_JSON));

        ClaudeIntentExtractionService service =
                new ClaudeIntentExtractionService(
                        restTemplate,
                        objectMapper,
                        API_URL,
                        "test-key",
                        MODEL);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.extract("查询今天的比特币价格"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        server.verify();
    }

    @Test
    void analysisSendsSystemPromptWithoutStructuredOutputAndReturnsText()
            throws Exception {

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.max_tokens").value(1024))
                .andExpect(jsonPath("$.system").exists())
                .andExpect(jsonPath("$.output_config").doesNotExist())
                .andRespond(withSuccess(
                        textResponse("行情分析结果"),
                        MediaType.APPLICATION_JSON));

        ClaudeAnalysisService service =
                new ClaudeAnalysisService(
                        restTemplate,
                        API_URL,
                        "test-key",
                        MODEL);

        String result = service.analyze(
                sampleMarketData(),
                "请分析当天行情");

        assertEquals("行情分析结果", result);
        server.verify();
    }

    @Test
    void restTemplateSendsAnthropicHeaders() throws Exception {
        RestTemplate restTemplate =
                new ClaudeRestTemplateConfig()
                        .claudeRestTemplate("test-key", "2023-06-01");

        MockRestServiceServer server =
                MockRestServiceServer.createServer(restTemplate);

        server.expect(requestTo(API_URL))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(header("Content-Type",
                        MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(
                        textResponse("ok"),
                        MediaType.APPLICATION_JSON));

        new ClaudeAnalysisService(
                restTemplate,
                API_URL,
                "test-key",
                MODEL).analyze(sampleMarketData(), null);

        server.verify();
    }

    private DailyOhlcvResponse sampleMarketData() {
        return new DailyOhlcvResponse(
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
    }

    private String textResponse(String text)
            throws Exception {

        return objectMapper.writeValueAsString(
                Map.of(
                        "stop_reason", "end_turn",
                        "content",
                        List.of(
                                Map.of(
                                        "type", "text",
                                        "text", text))));
    }
}
