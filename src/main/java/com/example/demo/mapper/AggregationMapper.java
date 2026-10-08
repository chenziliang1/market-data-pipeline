package com.example.demo.mapper;

import com.example.demo.entity.AggregatedTradeData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AggregationMapper {

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
                -- A minute with no trades is filled with the previous close, which may belong to the
                -- previous bucket. Open, high, low and close therefore come from minutes that traded,
                -- falling back to all minutes only when the whole bucket had no trades.
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
                COUNT(*) AS candle_count,
                symbol
            FROM source_rows
            GROUP BY symbol, bucket_start
            ORDER BY bucket_start
            """)
    List<AggregatedTradeData> findAggregated(
            @Param("symbol") String symbol,
            @Param("startTime") long startTime,
            @Param("endTime") long endTime,
            @Param("period") String period);
}