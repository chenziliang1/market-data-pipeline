package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks on a model-written market analysis. The figures a narrative may
 * cite are computed here, in code, and handed to the model; afterwards every number in the
 * text must be one of them (rounded or not), so the model cannot invent or miscalculate a
 * price or a percentage without being caught.
 */
public final class AnalysisGuard {

    static final int MAX_WORDS = 200;

    private static final Pattern NUMBER =
            Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?%?");

    private static final Pattern ISO_DATE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern SLASH_DATE =
            Pattern.compile("\\d{4}/\\d{1,2}/\\d{1,2}");

    private static final Pattern CJK_DATE =
            Pattern.compile("\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日");

    private static final String MONTH_NAMES =
            "January|February|March|April|May|June|July|August|September|October|November|December"
                    + "|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec";

    /** "January 2, 2024", "Jan 2 2024", "2 January 2024", "2nd of January, 2024". */
    private static final Pattern ENGLISH_DATE = Pattern.compile(
            "(?i)(?:(?:" + MONTH_NAMES + ")\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4}"
                    + "|\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:" + MONTH_NAMES + ")\\.?,?\\s+\\d{4})");

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
            problems.add("the analysis is empty");
            return problems;
        }

        if (!mentionsDate(analysis, data)) {
            problems.add("the date " + data.date() + " is not stated");
        }

        if (!analysis.contains("UTC")) {
            problems.add("the UTC timezone is not stated");
        }

        if (data.source() != null && !analysis.contains(data.source())) {
            problems.add("the data source " + data.source() + " is not stated");
        }

        if (!analysis.toLowerCase(Locale.ROOT).contains("investment advice")) {
            problems.add("the \"not investment advice\" reminder is missing");
        }

        long words = analysis.trim().split("\\s+").length;

        if (words > MAX_WORDS) {
            problems.add("longer than " + MAX_WORDS + " words (" + words + ")");
        }

        List<BigDecimal> allowedValues = List.of(
                data.open(), data.high(), data.low(), data.close(),
                data.volume(),
                data.tradeCount() == null ? BigDecimal.ZERO : BigDecimal.valueOf(data.tradeCount()),
                metrics.range(), metrics.change().abs());

        List<BigDecimal> allowedPercents = List.of(
                metrics.rangePct(), metrics.changePct().abs());

        String withoutDates = analysis;
        for (Pattern date : List.of(ISO_DATE, SLASH_DATE, CJK_DATE, ENGLISH_DATE)) {
            withoutDates = date.matcher(withoutDates).replaceAll(" ");
        }
        withoutDates = withoutDates.replaceAll("\\b" + data.date().getYear() + "\\b", " ");

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
                continue; // list numbering, small counts written as digits, etc.
            }

            List<BigDecimal> allowed = percent ? allowedPercents : allowedValues;

            if (allowed.stream().noneMatch(candidate -> cites(candidate, value))) {
                problems.add("cites a number the application did not supply: " + token);
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

        int year = data.date().getYear();
        int month = data.date().getMonthValue();
        int day = data.date().getDayOfMonth();
        String longMonth = data.date().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String shortMonth = data.date().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String monthNames = longMonth + "|" + shortMonth + (month == 9 ? "|Sept" : "");

        Pattern english = Pattern.compile(
                "(?i)(?:(?:" + monthNames + ")\\.?\\s+0?" + day + "(?:st|nd|rd|th)?,?\\s+" + year
                        + "|\\b0?" + day + "(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:" + monthNames + ")\\.?,?\\s+" + year + ")");

        Pattern cjk = Pattern.compile(
                year + "\\s*年\\s*0?" + month + "\\s*月\\s*0?" + day + "\\s*日");

        Pattern slash = Pattern.compile(
                year + "/0?" + month + "/0?" + day + "\\b");

        return english.matcher(analysis).find()
                || cjk.matcher(analysis).find()
                || slash.matcher(analysis).find();
    }
}
