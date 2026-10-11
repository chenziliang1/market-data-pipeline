package com.example.demo.eval;

import com.example.demo.config.ClaudeRestTemplateConfig;
import com.example.demo.dto.AnalysisResult;
import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.ParsedMarketQuery;
import com.example.demo.service.ClaudeAnalysisService;
import com.example.demo.service.ClaudeIntentExtractionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release gate for the terminal assistant, run against the real API. Skipped unless
 * ANTHROPIC_API_KEY is set, so it never runs in CI:
 *
 * <pre>
 * ANTHROPIC_API_KEY=... ./mvnw test -Dtest=ClaudeLiveEvalTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Intent: every question in eval/intent-questions.json must yield the expected symbol and
 * completed UTC date, or be rejected when it should be. Analysis: every day in
 * eval/analysis-days.json, asked two ways, must pass the deterministic checks in
 * AnalysisGuard. The report is written to target/eval-report.md.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeLiveEvalTest {

    private static final double INTENT_THRESHOLD =
            Double.parseDouble(System.getProperty("eval.intent.threshold", "0.9"));

    private static final double ANALYSIS_THRESHOLD =
            Double.parseDouble(System.getProperty("eval.analysis.threshold", "0.9"));

    private static final List<String> ANALYSIS_QUESTIONS = List.of(
            "",
            "Was this a volatile day, and did it close above or below the open?");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiUrl = env("CLAUDE_API_URL", "https://api.anthropic.com/v1/messages");
    private final String apiKey = System.getenv("ANTHROPIC_API_KEY");
    private final String model = env("CLAUDE_MODEL", "claude-sonnet-5-5");

    @Test
    void intentExtractionAndAnalysisMeetTheReleaseGate() throws Exception {
        RestTemplate restTemplate = new ClaudeRestTemplateConfig()
                .claudeRestTemplate(apiKey, env("CLAUDE_API_VERSION", "2023-06-01"));

        ClaudeIntentExtractionService intents = new ClaudeIntentExtractionService(
                restTemplate, objectMapper, apiUrl, apiKey, model);

        ClaudeAnalysisService analyses = new ClaudeAnalysisService(
                restTemplate, apiUrl, apiKey, model);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        StringBuilder report = new StringBuilder();
        report.append("# Assistant eval\n\nmodel: ").append(model)
                .append("  \nrun at (UTC): ").append(today).append("\n\n");

        // --- intent extraction -------------------------------------------------
        List<IntentCase> cases = load("eval/intent-questions.json", new TypeReference<>() {});
        assertThat(cases).as("intent question set").hasSizeGreaterThanOrEqualTo(27);
        int intentPassed = 0;
        report.append("## Intent extraction\n\n| # | tag | question | expected | got | pass |\n|---|---|---|---|---|---|\n");

        for (int i = 0; i < cases.size(); i++) {
            IntentCase c = cases.get(i);
            String expected = c.reject() ? "reject" : c.symbol() + " " + resolve(c.date(), today);
            String got;
            boolean pass;

            try {
                ParsedMarketQuery parsed = intents.extract(c.question());
                got = parsed.symbol() + " " + parsed.date();
                pass = !c.reject() && got.equals(expected);

            } catch (ResponseStatusException exception) {
                boolean rejected = exception.getStatusCode() == HttpStatus.BAD_REQUEST;
                got = (rejected ? "reject: " : "error: ") + exception.getReason();
                pass = c.reject() && rejected;
            }

            if (pass) {
                intentPassed++;
            }

            report.append(String.format("| %d | %s | %s | %s | %s | %s |%n",
                    i + 1, c.tag(), c.question(), expected, got.replace("|", "\\|"), pass ? "yes" : "**NO**"));
        }

        double intentRate = (double) intentPassed / cases.size();
        report.append(String.format("%n**%d / %d passed (%.1f%%)**%n%n", intentPassed, cases.size(), intentRate * 100));

        // --- analysis ----------------------------------------------------------
        List<DayFixture> days = load("eval/analysis-days.json", new TypeReference<>() {});
        int analysisTotal = 0;
        int analysisPassed = 0;
        report.append("## Analysis\n\n| date | question | words | problems |\n|---|---|---|---|\n");

        for (DayFixture day : days) {
            DailyOhlcvResponse data = day.toResponse();

            for (String question : ANALYSIS_QUESTIONS) {
                analysisTotal++;
                AnalysisResult result = analyses.analyze(data, question);
                int words = result.analysis().trim().split("\\s+").length;

                if (result.passed()) {
                    analysisPassed++;
                }

                report.append(String.format("| %s | %s | %d | %s |%n",
                        day.date(), question.isEmpty() ? "(default)" : question, words,
                        result.passed() ? "none" : "**" + String.join("; ", result.problems()) + "**"));
                report.append("\n<details><summary>text</summary>\n\n")
                        .append(result.analysis().replace("\n", "\n> ")).append("\n\n</details>\n\n");
            }
        }

        double analysisRate = (double) analysisPassed / analysisTotal;
        report.append(String.format("**%d / %d passed (%.1f%%)**%n", analysisPassed, analysisTotal, analysisRate * 100));

        Path out = Path.of("target", "eval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);

        System.out.printf("EVAL intent %d/%d (%.1f%%), analysis %d/%d (%.1f%%), report: %s%n",
                intentPassed, cases.size(), intentRate * 100,
                analysisPassed, analysisTotal, analysisRate * 100, out.toAbsolutePath());

        assertThat(intentRate).as("intent pass rate").isGreaterThanOrEqualTo(INTENT_THRESHOLD);
        assertThat(analysisRate).as("analysis pass rate").isGreaterThanOrEqualTo(ANALYSIS_THRESHOLD);
    }

    private static String resolve(String date, LocalDate today) {
        if (date.startsWith("{today")) {
            int offset = Integer.parseInt(date.substring("{today".length(), date.length() - 1));
            return today.plusDays(offset).toString();
        }

        if (date.startsWith("{year-1}")) {
            return today.minusYears(1).getYear() + date.substring("{year-1}".length());
        }

        return date;
    }

    private <T> T load(String resource, TypeReference<T> type) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return objectMapper.readValue(in, type);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IntentCase(String tag, String question, String symbol, String date, boolean reject) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DayFixture(String date, String open, String high, String low, String close,
                      String volume, long tradeCount) {

        DailyOhlcvResponse toResponse() {
            LocalDate day = LocalDate.parse(date);
            long start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

            return new DailyOhlcvResponse(
                    "BTCUSDT", day, "UTC", start, start + 86_400_000L - 1,
                    new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                    new BigDecimal(close), new BigDecimal(volume), tradeCount, "DATABASE");
        }
    }
}
