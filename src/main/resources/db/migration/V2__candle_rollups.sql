-- Hourly and daily candles, kept up to date by the consumer in the same transaction as the
-- minute candles they summarize, so a year of daily candles is read as 365 rows instead of
-- being aggregated from 525,600.
CREATE TABLE public.candle_rollup (
    symbol        VARCHAR(30)    NOT NULL,
    period        VARCHAR(8)     NOT NULL,  -- 'hour' or 'day'
    bucket_start  BIGINT         NOT NULL,  -- epoch milliseconds, UTC
    open_price    NUMERIC(20, 8),
    high_price    NUMERIC(20, 8),
    low_price     NUMERIC(20, 8),
    close_price   NUMERIC(20, 8),
    volume        NUMERIC,                  -- exact sum of the minute volumes
    nums_of_trade BIGINT,
    candle_count  INTEGER        NOT NULL,
    PRIMARY KEY (symbol, period, bucket_start)
);

-- Backfill from the minute candles already stored. The expressions match
-- AggregationMapper.OHLCV_COLUMNS: open, high, low and close come from minutes that traded.
INSERT INTO public.candle_rollup
SELECT symbol, p.period, open_time - MOD(open_time, p.bucket_millis) AS bucket_start,
       COALESCE((array_agg(open_price ORDER BY open_time) FILTER (WHERE nums_of_trade > 0))[1],
                (array_agg(open_price ORDER BY open_time))[1]),
       COALESCE(MAX(high_price) FILTER (WHERE nums_of_trade > 0), MAX(high_price)),
       COALESCE(MIN(low_price) FILTER (WHERE nums_of_trade > 0), MIN(low_price)),
       COALESCE((array_agg(close_price ORDER BY open_time DESC) FILTER (WHERE nums_of_trade > 0))[1],
                (array_agg(close_price ORDER BY open_time DESC))[1]),
       SUM(volume),
       SUM(nums_of_trade),
       COUNT(*)
FROM public.newtable
CROSS JOIN (VALUES ('hour', 3600000::BIGINT), ('day', 86400000::BIGINT)) AS p(period, bucket_millis)
WHERE symbol IS NOT NULL
GROUP BY symbol, p.period, bucket_start;
