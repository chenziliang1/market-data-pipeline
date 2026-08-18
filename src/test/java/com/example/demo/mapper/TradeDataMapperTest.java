package com.example.demo.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeDataMapperTest {

    @Test
    void completeCoverageRequiresNonNullMarketFields()
            throws Exception {

        Select select = TradeDataMapper.class
                .getMethod(
                        "hasCompleteMinuteCoverage",
                        String.class,
                        long.class,
                        long.class,
                        long.class)
                .getAnnotation(Select.class);

        String sql = String.join("\n", select.value());

        assertTrue(sql.contains("open_price IS NOT NULL"));
        assertTrue(sql.contains("high_price IS NOT NULL"));
        assertTrue(sql.contains("low_price IS NOT NULL"));
        assertTrue(sql.contains("close_price IS NOT NULL"));
        assertTrue(sql.contains("volume IS NOT NULL"));
        assertTrue(sql.contains("nums_of_trade IS NOT NULL"));
    }
}
