package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisGuardTest {

    private static final DailyOhlcvResponse DAY = new DailyOhlcvResponse(
            "BTCUSDT",
            LocalDate.of(2024, 1, 2),
            "UTC",
            1704153600000L,
            1704239999999L,
            new BigDecimal("43998.83000000"),
            new BigDecimal("45889.62000000"),
            new BigDecimal("43961.25000000"),
            new BigDecimal("44953.74000000"),
            new BigDecimal("399.57179000"),
            54282L,
            "DATABASE");

    private static final AnalysisGuard.Metrics METRICS = AnalysisGuard.metrics(DAY);

    @Test
    void metricsAreComputedInCode() {
        assertThat(METRICS.range()).isEqualByComparingTo("1928.37");
        assertThat(METRICS.rangePct()).isEqualByComparingTo("4.38");
        assertThat(METRICS.change()).isEqualByComparingTo("954.91");
        assertThat(METRICS.changePct()).isEqualByComparingTo("2.17");
    }

    @Test
    void analysisThatOnlyCitesSuppliedFiguresPasses() {
        String analysis = """
                数据说明：BTCUSDT 日线，日期 2024-01-02，UTC，来源 DATABASE。
                开盘 43,998.83，最高 45889.62，最低 43,961.25，收盘 44,953.7。
                振幅约 1,928 USDT，约 4.4%；收盘较开盘上涨约 954.91 USDT，涨幅 2.17%。
                成交量约 399.57 BTC，成交 54,282 笔。
                以上不是投资建议。
                """;

        assertThat(AnalysisGuard.check(DAY, METRICS, analysis)).isEmpty();
    }

    @Test
    void wrongPriceAndInventedPercentageAreFlagged() {
        String analysis = """
                2024年1月2日（UTC）BTCUSDT，来源 DATABASE。
                开盘 43,998.83，收盘 44,955.00，收盘位于区间 51% 处。
                这不是投资建议。
                """;

        List<String> problems = AnalysisGuard.check(DAY, METRICS, analysis);

        assertThat(problems).containsExactlyInAnyOrder(
                "引用了程序没有提供的数字: 44,955.00",
                "引用了程序没有提供的数字: 51%");
    }

    @Test
    void missingDateTimezoneSourceAndDisclaimerAreFlagged() {
        List<String> problems = AnalysisGuard.check(DAY, METRICS, "当天小幅上涨。");

        assertThat(problems).containsExactlyInAnyOrder(
                "没有写明数据日期 2024-01-02",
                "没有写明 UTC 时区",
                "没有写明数据来源 DATABASE",
                "缺少 \"不是投资建议\" 的提醒");
    }

    @Test
    void overlongAnalysisIsFlagged() {
        // "不是投资建议" is 6 CJK characters, so 295 more make 301.
        String analysis = "2024-01-02 UTC DATABASE 不是投资建议 " + "涨".repeat(295);

        assertThat(AnalysisGuard.check(DAY, METRICS, analysis))
                .containsExactly("超过 300 个汉字 (301)");
    }
}
