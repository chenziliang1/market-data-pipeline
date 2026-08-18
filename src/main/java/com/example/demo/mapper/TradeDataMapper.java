package com.example.demo.mapper;

import com.example.demo.entity.TradeData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TradeDataMapper {

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
            ON CONFLICT (symbol, open_time) DO NOTHING
            """)
    int insertTradeData(TradeData tradeData);

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
