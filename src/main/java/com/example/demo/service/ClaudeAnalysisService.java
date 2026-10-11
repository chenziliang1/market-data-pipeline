package com.example.demo.service;

import com.example.demo.dto.AnalysisResult;
import com.example.demo.dto.ClaudeMessageRequest;
import com.example.demo.dto.ClaudeMessageResponse;
import com.example.demo.dto.DailyOhlcvResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Asks the model for a short narrative about one verified daily candle. The range and the
 * change are computed in code and given to the model, and the answer is checked by
 * {@link AnalysisGuard} before it is returned.
 */
@Service
public class ClaudeAnalysisService {

    private static final int MAX_QUESTION_LENGTH = 2000;

    private final RestTemplate restTemplate;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public ClaudeAnalysisService(
            @Qualifier("claudeRestTemplate")
            RestTemplate restTemplate,
            @Value("${claude.api.url}")
            String apiUrl,
            @Value("${claude.api.key}")
            String apiKey,
            @Value("${claude.model}")
            String model) {

        this.restTemplate = restTemplate;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public AnalysisResult analyze(
            DailyOhlcvResponse marketData,
            String question) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "未配置 ANTHROPIC_API_KEY");
        }

        String normalizedQuestion =
                normalizeQuestion(question);

        AnalysisGuard.Metrics metrics =
                AnalysisGuard.metrics(marketData);

        ClaudeMessageRequest request =
                new ClaudeMessageRequest(
                        model,
                        1024,
                        """
                        你是比特币历史行情分析助手。

                        请使用简体中文回答。

                        只能使用应用程序提供的、已经验证的行情数据和指标。
                        只引用消息里给出的数字，可以四舍五入，
                        不要自己计算新的数字或百分比。
                        不得编造实时价格、新闻、事件原因或投资收益。
                        必须明确说明数据日期、UTC 时区和数据来源。
                        请将分析控制在 300 个汉字以内。
                        最后提醒用户这不是投资建议。
                        """,
                        List.of(
                                new ClaudeMessageRequest.Message(
                                        "user",
                                        buildPrompt(
                                                marketData,
                                                metrics,
                                                normalizedQuestion))),
                        null);

        try {
            ClaudeMessageResponse response =
                    restTemplate.postForObject(
                            apiUrl,
                            request,
                            ClaudeMessageResponse.class);

            String analysis = response == null
                    ? null
                    : response.firstText();

            if (analysis == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Claude 没有返回分析结果");
            }

            return new AnalysisResult(
                    analysis,
                    AnalysisGuard.check(marketData, metrics, analysis));

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Claude 分析请求失败",
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
            AnalysisGuard.Metrics metrics,
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

                程序计算的指标（直接引用，可以四舍五入）：

                range: %s USDT（最高减最低）
                rangePct: %s%%（range 占开盘价的比例）
                change: %s USDT（收盘减开盘，负数表示下跌）
                changePct: %s%%（change 占开盘价的比例）

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
                metrics.range().toPlainString(),
                metrics.rangePct().toPlainString(),
                metrics.change().toPlainString(),
                metrics.changePct().toPlainString(),
                question);
    }
}
