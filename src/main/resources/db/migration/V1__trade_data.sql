-- One row per one-minute candle. Idempotent, so it is safe on databases created before
-- Flyway was introduced (those are baselined at version 0 and run this script as a no-op).
CREATE TABLE IF NOT EXISTS public.newtable (
    open_time BIGINT NOT NULL,
    open_price NUMERIC(20, 8),
    high_price NUMERIC(20, 8),
    low_price NUMERIC(20, 8),
    close_price NUMERIC(20, 8),
    volume NUMERIC(20, 8),
    close_time BIGINT,
    nums_of_trade BIGINT,
    symbol VARCHAR(30),
    start_time BIGINT,
    end_time BIGINT
);

-- Required by INSERT ... ON CONFLICT (symbol, open_time).
CREATE UNIQUE INDEX IF NOT EXISTS newtable_symbol_open_time_uidx
    ON public.newtable (symbol, open_time);
