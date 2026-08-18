package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.KimiChatRequest;
import com.example.demo.dto.KimiChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class KimiAnalysisService {

    private static final int MAX_QUESTION_LENGTH = 2000;

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public KimiAnalysisService(
            @Qualifier("kimiRestTemplate")
            RestTemplate restTemplate,
            @Value("${kimi.api.url}")
            String apiUrl,
            @Value("${kimi.api.key}")
            String apiKey,
            @Value("${kimi.model}")
            String model) {

        this.restTemplate = restTemplate;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String analyze(
            DailyOhlcvResponse marketData,
            String question) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置 MOONSHOT_API_KEY");
        }

        String normalizedQuestion =
                normalizeQuestion(question);

        KimiChatRequest request =
                new KimiChatRequest(
                        model,
                        "low",
                        1024,
                        List.of(
                                new KimiChatRequest.Message(
                                        "system",
                                        """
                                        你是比特币历史行情分析助手。

                                        请使用简体中文回答。

                                        只能使用应用程序提供的、已经验证的行情数据。
                                        不得编造实时价格、新闻、事件原因或投资收益。
                                        必须明确说明数据日期、UTC 时区和数据来源。
                                        请将分析控制在 300 个汉字以内。
                                        最后提醒用户这不是投资建议。
                                        """),
                                new KimiChatRequest.Message(
                                        "user",
                                        buildPrompt(
                                                marketData,
                                                normalizedQuestion))),
                        null);

        try {
            KimiChatResponse response =
                    restTemplate.postForObject(
                            apiUrl,
                            request,
                            KimiChatResponse.class);

            if (response == null
                    || response.choices() == null
                    || response.choices().isEmpty()
                    || response.choices().get(0).message() == null
                    || response.choices()
                    .get(0)
                    .message()
                    .content() == null
                    || response.choices()
                    .get(0)
                    .message()
                    .content()
                    .isBlank()) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Kimi 没有返回分析结果");
            }

            return response.choices()
                    .get(0)
                    .message()
                    .content();

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Kimi 分析请求失败",
                    exception);
        }
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "请简要分析当天的价格表现和波动。";
        }

        String normalized = question.trim();

        if (normalized.length() > MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "问题不能超过 "
                            + MAX_QUESTION_LENGTH
                            + " 个字符");
        }

        return normalized;
    }

    private String buildPrompt(
            DailyOhlcvResponse data,
            String question) {

        return """
                已验证的比特币日线行情：

                symbol: %s
                date: %s
                timezone: %s
                source: %s

                open: %s
                high: %s
                low: %s
                close: %s
                volume: %s BTC
                tradeCount: %s

                用户原始问题：
                %s
                """.formatted(
                data.symbol(),
                data.date(),
                data.timezone(),
                data.source(),
                data.open().toPlainString(),
                data.high().toPlainString(),
                data.low().toPlainString(),
                data.close().toPlainString(),
                data.volume().toPlainString(),
                data.tradeCount(),
                question);
    }
}
