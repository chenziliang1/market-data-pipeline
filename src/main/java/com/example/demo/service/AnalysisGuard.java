package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks on a model-written market analysis. The figures a narrative may
 * cite are computed here, in code, and handed to the model; afterwards every number in the
 * text must be one of them (rounded or not), so the model cannot invent or miscalculate a
 * price or a percentage without being caught.
 */
public final class AnalysisGuard {

    static final int MAX_CJK_CHARS = 300;

    private static final Pattern NUMBER =
            Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?%?");

    private static final Pattern ISO_DATE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern SLASH_DATE =
            Pattern.compile("\\d{4}/\\d{1,2}/\\d{1,2}");

    private static final Pattern CJK_DATE =
            Pattern.compile("\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日");

    private static final Pattern CJK_CHAR =
            Pattern.compile("[\\u4e00-\\u9fff]");

    private AnalysisGuard() {
    }

    /** Figures derived from the candle: range, change, and both as a percentage of the open. */
    public record Metrics(
            BigDecimal range,
            BigDecimal rangePct,
            BigDecimal change,
            BigDecimal changePct) {
    }

    public static Metrics metrics(DailyOhlcvResponse data) {
        BigDecimal range = data.high().subtract(data.low());
        BigDecimal change = data.close().subtract(data.open());

        return new Metrics(
                range.stripTrailingZeros(),
                percentOfOpen(range, data.open()),
                change.stripTrailingZeros(),
                percentOfOpen(change, data.open()));
    }

    private static BigDecimal percentOfOpen(BigDecimal value, BigDecimal open) {
        if (open == null || open.signum() == 0) {
            return BigDecimal.ZERO;
        }

        return value.multiply(BigDecimal.valueOf(100))
                .divide(open, 2, RoundingMode.HALF_UP);
    }

    /** Returns the problems found; an empty list means the analysis passed every check. */
    public static List<String> check(
            DailyOhlcvResponse data,
            Metrics metrics,
            String analysis) {

        List<String> problems = new ArrayList<>();

        if (analysis == null || analysis.isBlank()) {
            problems.add("分析为空");
            return problems;
        }

        if (!mentionsDate(analysis, data)) {
            problems.add("没有写明数据日期 " + data.date());
        }

        if (!analysis.contains("UTC")) {
            problems.add("没有写明 UTC 时区");
        }

        if (data.source() != null && !analysis.contains(data.source())) {
            problems.add("没有写明数据来源 " + data.source());
        }

        if (!analysis.contains("投资建议")) {
            problems.add("缺少 \"不是投资建议\" 的提醒");
        }

        long cjkChars = CJK_CHAR.matcher(analysis).results().count();

        if (cjkChars > MAX_CJK_CHARS) {
            problems.add("超过 " + MAX_CJK_CHARS + " 个汉字 (" + cjkChars + ")");
        }

        List<BigDecimal> allowedValues = List.of(
                data.open(), data.high(), data.low(), data.close(),
                data.volume(),
                data.tradeCount() == null ? BigDecimal.ZERO : BigDecimal.valueOf(data.tradeCount()),
                metrics.range(), metrics.change().abs());

        List<BigDecimal> allowedPercents = List.of(
                metrics.rangePct(), metrics.changePct().abs());

        String withoutDates = CJK_DATE.matcher(
                SLASH_DATE.matcher(
                        ISO_DATE.matcher(analysis).replaceAll(" "))
                        .replaceAll(" "))
                .replaceAll(" ");

        Matcher matcher = NUMBER.matcher(withoutDates);

        while (matcher.find()) {
            String token = matcher.group();
            boolean percent = token.endsWith("%");
            String digits = token.replace(",", "").replace("%", "");

            if (digits.endsWith(".")) {
                digits = digits.substring(0, digits.length() - 1);
            }

            BigDecimal value;
            try {
                value = new BigDecimal(digits);
            } catch (NumberFormatException exception) {
                continue;
            }

            if (!percent && value.scale() <= 0 && value.compareTo(BigDecimal.valueOf(100)) < 0) {
                continue; // list numbering, "两" "三" written as digits, etc.
            }

            List<BigDecimal> allowed = percent ? allowedPercents : allowedValues;

            if (allowed.stream().noneMatch(candidate -> cites(candidate, value))) {
                problems.add("引用了程序没有提供的数字: " + token);
            }
        }

        return problems;
    }

    /** True when the cited value equals the candidate rounded (or truncated) to the cited precision. */
    private static boolean cites(BigDecimal candidate, BigDecimal cited) {
        int scale = Math.max(cited.scale(), 0);

        if (scale > candidate.scale()) {
            return candidate.compareTo(cited) == 0;
        }

        return candidate.setScale(scale, RoundingMode.HALF_UP).compareTo(cited) == 0
                || candidate.setScale(scale, RoundingMode.DOWN).compareTo(cited) == 0;
    }

    private static boolean mentionsDate(String analysis, DailyOhlcvResponse data) {
        if (analysis.contains(data.date().toString())) {
            return true;
        }

        Pattern cjk = Pattern.compile(
                data.date().getYear() + "\\s*年\\s*0?" + data.date().getMonthValue()
                        + "\\s*月\\s*0?" + data.date().getDayOfMonth() + "\\s*日");

        Pattern slash = Pattern.compile(
                data.date().getYear() + "/0?" + data.date().getMonthValue()
                        + "/0?" + data.date().getDayOfMonth());

        return cjk.matcher(analysis).find() || slash.matcher(analysis).find();
    }
}
