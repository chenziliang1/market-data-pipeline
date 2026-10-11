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

    private static final String SYSTEM_PROMPT = """
            You are a Bitcoin historical market-data assistant.

            Answer in English.

            Use only the verified market data and the metrics supplied by the
            application. Quote only the numbers given in the message (rounding is
            fine); do not compute new figures or percentages of your own.
            Do not invent live prices, news, causes or investment returns.
            State the data date, the UTC timezone and the data source explicitly.
            Keep the analysis under %d words.
            End by reminding the user that this is not investment advice.
            """.formatted(AnalysisGuard.MAX_WORDS);

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
                    "ANTHROPIC_API_KEY is not configured");
        }

        String normalizedQuestion =
                normalizeQuestion(question);

        AnalysisGuard.Metrics metrics =
                AnalysisGuard.metrics(marketData);

        ClaudeMessageRequest request =
                new ClaudeMessageRequest(
                        model,
                        1024,
                        SYSTEM_PROMPT,
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
                        "Claude returned no analysis");
            }

            return new AnalysisResult(
                    analysis,
                    AnalysisGuard.check(marketData, metrics, analysis));

        } catch (ResponseStatusException exception) {
            throw exception;

        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The Claude analysis request failed",
                    exception);
        }
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "Briefly describe the day's price action and volatility.";
        }

        String normalized = question.trim();

        if (normalized.length() > MAX_QUESTION_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The question may not exceed "
                            + MAX_QUESTION_LENGTH
                            + " characters");
        }

        return normalized;
    }

    private String buildPrompt(
            DailyOhlcvResponse data,
            AnalysisGuard.Metrics metrics,
            String question) {

        return """
                Verified Bitcoin daily candle:

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

                Metrics computed by the application (quote them directly; rounding is fine):

                range: %s USDT (high minus low)
                rangePct: %s%% (range as a share of the open)
                change: %s USDT (close minus open; negative means a decline)
                changePct: %s%% (change as a share of the open)

                The user's original question:
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
