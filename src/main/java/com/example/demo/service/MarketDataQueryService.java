package com.example.demo.service;

import com.example.demo.dto.DailyOhlcvResponse;
import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.example.demo.mapper.AggregationMapper;
import com.example.demo.mapper.TradeDataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Service
public class MarketDataQueryService {

    private static final Logger logger =
            LoggerFactory.getLogger(
                    MarketDataQueryService.class);

    private static final long MINUTES_PER_UTC_DAY = 1440L;
    private static final String SUPPORTED_SYMBOL = "BTCUSDT";

    private final TradeDataMapper tradeDataMapper;
    private final AggregationMapper aggregationMapper;
    private final BinanceDailyMarketService binanceDailyMarketService;

    public MarketDataQueryService(
            TradeDataMapper tradeDataMapper,
            AggregationMapper aggregationMapper,
            BinanceDailyMarketService binanceDailyMarketService) {

        this.tradeDataMapper = tradeDataMapper;
        this.aggregationMapper = aggregationMapper;
        this.binanceDailyMarketService =
                binanceDailyMarketService;
    }

    public DailyOhlcvResponse getDaily(
            String symbol,
            LocalDate date) {

        String normalizedSymbol =
                normalizeSymbol(symbol);

        validateDate(date);

        long startTime = date
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();

        long endTime = date
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();

        try {
            boolean databaseComplete =
                    tradeDataMapper
                            .hasCompleteMinuteCoverage(
                                    normalizedSymbol,
                                    startTime,
                                    endTime,
                                    MINUTES_PER_UTC_DAY);

            if (databaseComplete) {
                List<AggregatedTradeData> rows =
                        aggregationMapper.findAggregated(
                                normalizedSymbol,
                                startTime,
                                endTime,
                                AggregationPeriod.DAILY
                                        .getPostgresValue());

                if (rows.size() == 1) {
                    return fromDatabase(
                            normalizedSymbol,
                            date,
                            startTime,
                            endTime,
                            rows.get(0));
                }
            }

        } catch (DataAccessException exception) {
            logger.warn(
                    "数据库查询失败，改用 Binance.US：{} {}",
                    normalizedSymbol,
                    date,
                    exception);
        }

        return binanceDailyMarketService.fetchDaily(
                normalizedSymbol,
                date,
                startTime,
                endTime);
    }

    private String normalizeSymbol(String symbol) {
        String normalized =
                symbol == null || symbol.isBlank()
                        ? SUPPORTED_SYMBOL
                        : symbol.trim()
                        .toUpperCase(Locale.ROOT);

        if (!SUPPORTED_SYMBOL.equals(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "目前只支持 BTCUSDT");
        }

        return normalized;
    }

    private void validateDate(LocalDate date) {
        if (date == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "必须提供日期");
        }

        if (!date.isBefore(
                LocalDate.now(ZoneOffset.UTC))) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "只能查询已经结束的 UTC 日期");
        }
    }

    private DailyOhlcvResponse fromDatabase(
            String symbol,
            LocalDate date,
            long startTime,
            long endTime,
            AggregatedTradeData data) {

        return new DailyOhlcvResponse(
                symbol,
                date,
                "UTC",
                startTime,
                endTime - 1,
                data.getOpenPrice(),
                data.getHighPrice(),
                data.getLowPrice(),
                data.getClosePrice(),
                data.getVolume(),
                data.getNumsOfTrade(),
                "DATABASE");
    }
}