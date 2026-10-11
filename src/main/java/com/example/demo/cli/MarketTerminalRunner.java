package com.example.demo.cli;

import com.example.demo.dto.AnalysisResult;
import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.ParsedMarketQuery;
import com.example.demo.service.ClaudeAnalysisService;
import com.example.demo.service.ClaudeIntentExtractionService;
import com.example.demo.service.MarketDataQueryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

@Component
@ConditionalOnProperty(
        name = "app.terminal-chat.enabled",
        havingValue = "true")
public class MarketTerminalRunner
        implements ApplicationRunner {

    private final ClaudeIntentExtractionService
            intentExtractionService;

    private final MarketDataQueryService
            marketDataQueryService;

    private final ClaudeAnalysisService
            analysisService;

    public MarketTerminalRunner(
            ClaudeIntentExtractionService
                    intentExtractionService,
            MarketDataQueryService
                    marketDataQueryService,
            ClaudeAnalysisService
                    analysisService) {

        this.intentExtractionService =
                intentExtractionService;

        this.marketDataQueryService =
                marketDataQueryService;

        this.analysisService =
                analysisService;
    }

    @Override
    public void run(ApplicationArguments args)
            throws IOException {

        BufferedReader input =
                new BufferedReader(
                        new InputStreamReader(System.in));

        PrintWriter output =
                new PrintWriter(System.out, true);

        output.println();
        output.println("Claude Bitcoin market terminal is ready.");
        output.println("Every question must name one date.");
        output.println("Relative dates such as yesterday are interpreted in UTC.");
        output.println(
                "Example: Show me Bitcoin's open, high, low and close for January 2, 2024 and comment on the volatility");
        output.println(
                "Type exit or quit to leave the question loop.");

        while (!Thread.currentThread().isInterrupted()) {
            output.print("\nyou> ");
            output.flush();

            String question = input.readLine();

            if (question == null) {
                return;
            }

            question = question.trim();

            if (question.isEmpty()) {
                continue;
            }

            if (isExitCommand(question)) {
                output.println(
                        "Left the question loop; press Ctrl+C to stop Spring Boot.");
                return;
            }

            try {
                ParsedMarketQuery parsed =
                        intentExtractionService.extract(
                                question);

                output.printf(
                        "Understood: %s, %s (UTC)%n",
                        parsed.symbol(),
                        parsed.date());

                DailyOhlcvResponse data =
                        marketDataQueryService.getDaily(
                                parsed.symbol(),
                                parsed.date());

                printMarketData(output, data);

                try {
                    AnalysisResult result =
                            analysisService.analyze(
                                    data,
                                    question);

                    output.println("\nClaude analysis:");
                    output.println(result.analysis());

                    if (!result.passed()) {
                        output.println(
                                "\nThe analysis failed the application's checks; rely on the market data above only:");
                        result.problems().forEach(
                                problem -> output.println("- " + problem));
                    }

                } catch (RuntimeException exception) {
                    output.println(
                            "\nMarket data retrieved, but the Claude analysis failed: "
                                    + userMessage(exception));
                }

            } catch (RuntimeException exception) {
                output.println(
                        "Could not answer: "
                                + userMessage(exception));
            }
        }
    }

    private void printMarketData(
            PrintWriter output,
            DailyOhlcvResponse data) {

        output.println("\nVerified daily candle:");

        output.printf(
                "Date: %s (%s)%n",
                data.date(),
                data.timezone());

        output.printf(
                "Open: %s USDT%n",
                data.open().toPlainString());

        output.printf(
                "High: %s USDT%n",
                data.high().toPlainString());

        output.printf(
                "Low: %s USDT%n",
                data.low().toPlainString());

        output.printf(
                "Close: %s USDT%n",
                data.close().toPlainString());

        output.printf(
                "Volume: %s BTC%n",
                data.volume().toPlainString());

        output.printf(
                "Trades: %s%n",
                data.tradeCount() == null
                        ? "N/A"
                        : data.tradeCount().toString());

        output.printf(
                "Source: %s%n",
                data.source());
    }

    private boolean isExitCommand(String value) {
        return "exit".equalsIgnoreCase(value)
                || "quit".equalsIgnoreCase(value);
    }

    private String userMessage(
            RuntimeException exception) {

        if (exception
                instanceof ResponseStatusException statusException
                && statusException.getReason() != null) {

            return statusException.getReason();
        }

        String message = exception.getMessage();

        return message == null || message.isBlank()
                ? "unknown error"
                : message;
    }
}
