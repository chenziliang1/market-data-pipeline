package com.example.demo.mapper;

import com.example.demo.entity.TradeData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TradeDataMapper {

    @Insert("INSERT INTO newtable (open_time, open_price, high_price, low_price, close_price, volume, close_time, nums_of_trade, symbol) " +
            "VALUES (#{openTime}, #{openPrice}, #{highPrice}, #{lowPrice}, #{closePrice}, #{volume}, #{closeTime}, #{numsOfTrade}, #{symbol})" + "ON CONFLICT (symbol, open_time) DO NOTHING")
    int insertTradeData(TradeData tradeData);
}