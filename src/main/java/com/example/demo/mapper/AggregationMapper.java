package com.example.demo.mapper;

import com.example.demo.entity.AggregatedTradeData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AggregationMapper {

    /**
     * Open, high, low and close of a bucket, plus its totals, from the minute candles in it.
     * A minute with no trades is filled with the previous close, which may belong to the previous
     * bucket. Open, high, low and close therefore come from minutes that traded, falling back to
     * all minutes only when the whole bucket had no trades.
     * The backfill in V2__candle_rollups.sql uses the same expressions.
     */
    String OHLCV_COLUMNS = """
                COALESCE(
                    (array_agg(open_price ORDER BY open_time) FILTER (WHERE nums_of_trade > 0))[1],
                    (array_agg(open_price ORDER BY open_time))[1])
                    AS open_price,
                COALESCE(
                    MAX(high_price) FILTER (WHERE nums_of_trade > 0),
                    MAX(high_price))
                    AS high_price,
                COALESCE(
                    MIN(low_price) FILTER (WHERE nums_of_trade > 0),
                    MIN(low_price))
                    AS low_price,
                COALESCE(
                    (array_agg(close_price ORDER BY open_time DESC) FILTER (WHERE nums_of_trade > 0))[1],
                    (array_agg(close_price ORDER BY open_time DESC))[1])
                    AS close_price,
                SUM(volume) AS volume,
                SUM(nums_of_trade) AS nums_of_trade,
                COUNT(*) AS candle_count
            """;

    /** Aggregates minute candles directly. Used for partial buckets at the edges of a range. */
    @Select("""
            WITH source_rows AS (
                SELECT *,
                       date_trunc(
                           #{period},
                           timezone('UTC', to_timestamp(open_time / 1000.0))
                       ) AS bucket_start
                FROM newtable
                WHERE symbol = #{symbol}
                  AND open_time >= #{startTime}
                  AND open_time < #{endTime}
            )
            SELECT
                CAST(EXTRACT(EPOCH FROM bucket_start) * 1000 AS BIGINT)
                    AS bucket_start_time,
            """ + OHLCV_COLUMNS + """
                , symbol
            FROM source_rows
            GROUP BY symbol, bucket_start
            ORDER BY bucket_start
            """)
    List<AggregatedTradeData> findAggregated(
            @Param("symbol") String symbol,
            @Param("startTime") long startTime,
            @Param("endTime") long endTime,
            @Param("period") String period);

    /** Reads whole buckets from the rollup table; both bounds must be bucket boundaries. */
    @Select("""
            SELECT bucket_start AS bucket_start_time,
                   open_price, high_price, low_price, close_price,
                   volume, nums_of_trade, candle_count, symbol
            FROM candle_rollup
            WHERE symbol = #{symbol}
              AND period = #{period}
              AND bucket_start >= #{startTime}
              AND bucket_start < #{endTime}
            ORDER BY bucket_start
            """)
    List<AggregatedTradeData> findRollups(
            @Param("symbol") String symbol,
            @Param("startTime") long startTime,
            @Param("endTime") long endTime,
            @Param("period") String period);

    /**
     * Recomputes the rollups of every bucket in [startTime, endTime) from the minute candles.
     * Both bounds must be bucket boundaries.
     */
    @Insert("""
            INSERT INTO candle_rollup (
                symbol, period, bucket_start,
                open_price, high_price, low_price, close_price,
                volume, nums_of_trade, candle_count
            )
            SELECT symbol, #{period}, open_time - MOD(open_time, #{bucketMillis}) AS bucket_start,
            """ + OHLCV_COLUMNS + """
            FROM newtable
            WHERE symbol = #{symbol}
              AND open_time >= #{startTime}
              AND open_time < #{endTime}
            GROUP BY symbol, bucket_start
            ON CONFLICT (symbol, period, bucket_start) DO UPDATE SET
                open_price = EXCLUDED.open_price,
                high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume = EXCLUDED.volume,
                nums_of_trade = EXCLUDED.nums_of_trade,
                candle_count = EXCLUDED.candle_count
            """)
    int refreshRollups(
            @Param("symbol") String symbol,
            @Param("period") String period,
            @Param("bucketMillis") long bucketMillis,
            @Param("startTime") long startTime,
            @Param("endTime") long endTime);
}
