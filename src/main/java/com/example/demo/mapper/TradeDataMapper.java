package com.example.demo.mapper;

import com.example.demo.entity.TradeData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TradeDataMapper {

    /**
     * Inserts a candle, or replaces the stored values when a corrected version arrives.
     *
     * @return 1 if a row was inserted or changed, 0 for an identical redelivery
     */
    @Insert("""
            INSERT INTO newtable (
                open_time,
                open_price,
                high_price,
                low_price,
                close_price,
                volume,
                close_time,
                nums_of_trade,
                symbol
            )
            VALUES (
                #{openTime},
                #{openPrice},
                #{highPrice},
                #{lowPrice},
                #{closePrice},
                #{volume},
                #{closeTime},
                #{numsOfTrade},
                #{symbol}
            )
            ON CONFLICT (symbol, open_time) DO UPDATE SET
                open_price = EXCLUDED.open_price,
                high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume = EXCLUDED.volume,
                close_time = EXCLUDED.close_time,
                nums_of_trade = EXCLUDED.nums_of_trade
            WHERE (newtable.open_price, newtable.high_price, newtable.low_price,
                   newtable.close_price, newtable.volume, newtable.close_time,
                   newtable.nums_of_trade)
                  IS DISTINCT FROM
                  (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price,
                   EXCLUDED.close_price, EXCLUDED.volume, EXCLUDED.close_time,
                   EXCLUDED.nums_of_trade)
            """)
    int upsertTradeData(TradeData tradeData);

    /**
     * Upserts many candles in one statement, so one round trip and one transaction per batch.
     * The batch must not contain the same (symbol, open_time) twice: PostgreSQL rejects a
     * statement that would update the same row twice.
     *
     * @return the symbol and open time of every row that was inserted or changed
     */
    @Select("""
            <script>
            INSERT INTO newtable (
                open_time, open_price, high_price, low_price, close_price,
                volume, close_time, nums_of_trade, symbol
            )
            VALUES
            <foreach collection="candles" item="c" separator=",">
                (#{c.openTime}, #{c.openPrice}, #{c.highPrice}, #{c.lowPrice}, #{c.closePrice},
                 #{c.volume}, #{c.closeTime}, #{c.numsOfTrade}, #{c.symbol})
            </foreach>
            ON CONFLICT (symbol, open_time) DO UPDATE SET
                open_price = EXCLUDED.open_price,
                high_price = EXCLUDED.high_price,
                low_price = EXCLUDED.low_price,
                close_price = EXCLUDED.close_price,
                volume = EXCLUDED.volume,
                close_time = EXCLUDED.close_time,
                nums_of_trade = EXCLUDED.nums_of_trade
            WHERE (newtable.open_price, newtable.high_price, newtable.low_price,
                   newtable.close_price, newtable.volume, newtable.close_time,
                   newtable.nums_of_trade)
                  IS DISTINCT FROM
                  (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price,
                   EXCLUDED.close_price, EXCLUDED.volume, EXCLUDED.close_time,
                   EXCLUDED.nums_of_trade)
            RETURNING symbol, open_time
            </script>
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<TradeData> upsertTradeDataBatch(@Param("candles") List<TradeData> candles);

    /**
     * Serializes writers of the same symbol until the current transaction ends, so two
     * transactions never recompute the same rollup from different snapshots of the minute candles.
     */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(#{symbol}))) AS locked")
    Integer lockSymbol(@Param("symbol") String symbol);

    @Select("""
            SELECT COUNT(*) = #{expectedCount}
               AND COALESCE(MIN(open_time), -1) = #{startTime}
               AND COALESCE(MAX(open_time), -1) = #{endTime} - 60000
               AND COUNT(*) FILTER (
                     WHERE MOD(open_time - #{startTime}, 60000) = 0
                   ) = #{expectedCount}
               AND COUNT(*) FILTER (
                     WHERE open_price IS NOT NULL
                       AND high_price IS NOT NULL
                       AND low_price IS NOT NULL
                       AND close_price IS NOT NULL
                       AND volume IS NOT NULL
                       AND nums_of_trade IS NOT NULL
                   ) = #{expectedCount}
            FROM newtable
            WHERE symbol = #{symbol}
              AND open_time >= #{startTime}
              AND open_time < #{endTime}
            """)
    boolean hasCompleteMinuteCoverage(
            @Param("symbol") String symbol,
            @Param("startTime") long startTime,
            @Param("endTime") long endTime,
            @Param("expectedCount") long expectedCount);
}
