package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.dto.DailyReconciliationReport;
import com.example.demo.dto.DailyReconciliationReport.DayDiscrepancy;
import com.example.demo.dto.DailyReconciliationReport.FieldDifference;
import com.example.demo.dto.DailyReconciliationReport.Status;
import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.example.demo.mapper.AggregationMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Checks the stored data against an independent source: each closed UTC day aggregated from
 * our minute candles must equal the exchange's own daily candle, field by field.
 */
@Service
public class DailyReconciliationService {

    private static final long MINUTES_PER_DAY = 1_440L;
    private static final long MAX_DAYS = 366L;
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[A-Z0-9]{2,20}");

    private final AggregationMapper aggregationMapper;
    private final BinanceDailyMarketService binanceDailyMarketService;
    private final Clock clock;

    public DailyReconciliationService(
            AggregationMapper aggregationMapper,
            BinanceDailyMarketService binanceDailyMarketService,
            Clock clock) {
        this.aggregationMapper = aggregationMapper;
        this.binanceDailyMarketService = binanceDailyMarketService;
        this.clock = clock;
    }

    /** Reconciles every UTC day from {@code from} to {@code to}, both inclusive. */
    public DailyReconciliationReport reconcile(String symbol, LocalDate from, LocalDate to) {
        String normalizedSymbol = normalizeSymbol(symbol);
        validateDates(from, to);

        long startTime = startOfDay(from);
        long endTime = startOfDay(to.plusDays(1));

        Map<Long, AggregatedTradeData> stored = new HashMap<>();
        aggregationMapper.findAggregated(
                        normalizedSymbol, startTime, endTime, AggregationPeriod.DAILY.getPostgresValue())
                .forEach(row -> stored.put(row.getBucketStartTime(), row));
        Map<Long, DailyOhlcvResponse> exchange =
                binanceDailyMarketService.fetchDailyRange(normalizedSymbol, startTime, endTime);

        int checked = 0;
        int matched = 0;
        int mismatched = 0;
        int incomplete = 0;
        int missingOnExchange = 0;
        List<DayDiscrepancy> discrepancies = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            checked++;
            long dayStart = startOfDay(date);
            AggregatedTradeData row = stored.get(dayStart);
            long candleCount = row == null || row.getCandleCount() == null ? 0 : row.getCandleCount();

            // A partial day would differ from the exchange for a known reason, so it is reported, not compared.
            if (candleCount < MINUTES_PER_DAY) {
                incomplete++;
                discrepancies.add(new DayDiscrepancy(date, Status.INCOMPLETE, candleCount, List.of()));
                continue;
            }

            DailyOhlcvResponse reference = exchange.get(dayStart);
            if (reference == null) {
                missingOnExchange++;
                discrepancies.add(new DayDiscrepancy(date, Status.MISSING_ON_EXCHANGE, candleCount, List.of()));
                continue;
            }

            List<FieldDifference> differences = compare(row, reference);
            if (differences.isEmpty()) {
                matched++;
            } else {
                mismatched++;
                discrepancies.add(new DayDiscrepancy(date, Status.MISMATCH, candleCount, differences));
            }
        }

        return new DailyReconciliationReport(
                normalizedSymbol, from, to, checked, matched, mismatched, incomplete, missingOnExchange,
                discrepancies);
    }

    private static List<FieldDifference> compare(AggregatedTradeData stored, DailyOhlcvResponse exchange) {
        List<FieldDifference> differences = new ArrayList<>();
        compareDecimal("open", stored.getOpenPrice(), exchange.open(), differences);
        compareDecimal("high", stored.getHighPrice(), exchange.high(), differences);
        compareDecimal("low", stored.getLowPrice(), exchange.low(), differences);
        compareDecimal("close", stored.getClosePrice(), exchange.close(), differences);
        compareDecimal("volume", stored.getVolume(), exchange.volume(), differences);
        if (!Objects.equals(stored.getNumsOfTrade(), exchange.tradeCount())) {
            differences.add(new FieldDifference(
                    "tradeCount", String.valueOf(stored.getNumsOfTrade()), String.valueOf(exchange.tradeCount())));
        }
        return differences;
    }

    /** Decimals are compared by value, so 1.5 and 1.50000000 are equal. */
    private static void compareDecimal(
            String field, BigDecimal stored, BigDecimal exchange, List<FieldDifference> differences) {
        boolean equal = stored == null ? exchange == null : exchange != null && stored.compareTo(exchange) == 0;
        if (!equal) {
            differences.add(new FieldDifference(
                    field,
                    stored == null ? null : stored.toPlainString(),
                    exchange == null ? null : exchange.toPlainString()));
        }
    }

    private void validateDates(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (!to.isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            throw new IllegalArgumentException("only UTC days that have already ended can be reconciled");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("at most " + MAX_DAYS + " days can be reconciled at once");
        }
    }

    private static String normalizeSymbol(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("symbol must be 2-20 letters or digits, for example BTCUSDT");
        }
        return normalized;
    }

    private static long startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
