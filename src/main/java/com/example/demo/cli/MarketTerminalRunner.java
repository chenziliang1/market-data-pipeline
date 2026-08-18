package com.example.demo.cli;

import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.ParsedMarketQuery;
import com.example.demo.service.KimiAnalysisService;
import com.example.demo.service.KimiIntentExtractionService;
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

    private final KimiIntentExtractionService
            intentExtractionService;

    private final MarketDataQueryService
            marketDataQueryService;

    private final KimiAnalysisService
            kimiAnalysisService;

    public MarketTerminalRunner(
            KimiIntentExtractionService
                    intentExtractionService,
            MarketDataQueryService
                    marketDataQueryService,
            KimiAnalysisService
                    kimiAnalysisService) {

        this.intentExtractionService =
                intentExtractionService;

        this.marketDataQueryService =
                marketDataQueryService;

        this.kimiAnalysisService =
                kimiAnalysisService;
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
        output.println("Kimi 比特币行情终端已启动。");
        output.println("每个问题请包含一个日期。");
        output.println("昨天、前天等相对日期按照 UTC 解释。");
        output.println(
                "示例：查询2024年1月2日比特币的开高低收，并分析波动");
        output.println(
                "输入 exit、quit 或 退出，可退出问答模式。");

        while (!Thread.currentThread().isInterrupted()) {
            output.print("\n你> ");
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
                        "已退出问答模式；按 Ctrl+C 停止 Spring Boot。");
                return;
            }

            try {
                ParsedMarketQuery parsed =
                        intentExtractionService.extract(
                                question);

                output.printf(
                        "识别结果：%s，%s（UTC）%n",
                        parsed.symbol(),
                        parsed.date());

                DailyOhlcvResponse data =
                        marketDataQueryService.getDaily(
                                parsed.symbol(),
                                parsed.date());

                printMarketData(output, data);

                try {
                    String analysis =
                            kimiAnalysisService.analyze(
                                    data,
                                    question);

                    output.println("\nKimi 分析：");
                    output.println(analysis);

                } catch (RuntimeException exception) {
                    output.println(
                            "\n行情已查到，但 Kimi 分析失败："
                                    + userMessage(exception));
                }

            } catch (RuntimeException exception) {
                output.println(
                        "处理失败："
                                + userMessage(exception));
            }
        }
    }

    private void printMarketData(
            PrintWriter output,
            DailyOhlcvResponse data) {

        output.println("\n已验证日线行情：");

        output.printf(
                "日期：%s (%s)%n",
                data.date(),
                data.timezone());

        output.printf(
                "开盘：%s USDT%n",
                data.open().toPlainString());

        output.printf(
                "最高：%s USDT%n",
                data.high().toPlainString());

        output.printf(
                "最低：%s USDT%n",
                data.low().toPlainString());

        output.printf(
                "收盘：%s USDT%n",
                data.close().toPlainString());

        output.printf(
                "成交量：%s BTC%n",
                data.volume().toPlainString());

        output.printf(
                "成交笔数：%s%n",
                data.tradeCount() == null
                        ? "N/A"
                        : data.tradeCount().toString());

        output.printf(
                "数据源：%s%n",
                data.source());
    }

    private boolean isExitCommand(String value) {
        return "exit".equalsIgnoreCase(value)
                || "quit".equalsIgnoreCase(value)
                || "退出".equals(value);
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
                ? "未知错误"
                : message;
    }
}