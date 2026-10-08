package com.example.demo;

import com.example.demo.dto.DailyReconciliationReport;
import com.example.demo.entity.AggregatedTradeData;
import com.example.demo.entity.AggregationPeriod;
import com.example.demo.entity.TradeData;
import com.example.demo.service.AggregationService;
import com.example.demo.service.DailyReconciliationService;
import com.example.demo.service.TradeDataProducer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * End-to-end checks against real Kafka, PostgreSQL and Redis containers.
 * The tests are skipped, not failed, when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PipelineIntegrationTest {

    private static final String TOPIC = "trade-data-it-" + UUID.randomUUID();
    private static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";
    private static final String SYMBOL = "BTCUSDT";
    private static final long MINUTE = 60_000L;
    /** 2023-11-14T21:00:00Z, aligned to an hour boundary. */
    private static final long HOUR_START = 1_699_999_200_000L;
    private static final long HOUR_END = HOUR_START + 60 * MINUTE;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "it-" + UUID.randomUUID());
        registry.add("app.kafka.topics.trade-data", () -> TOPIC);
        registry.add("app.kafka.retry.initial-interval", () -> "PT0.1S");
        registry.add("app.kafka.retry.max-retries", () -> "1");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TradeDataProducer producer;

    @Autowired
    private AggregationService aggregationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private DailyReconciliationService reconciliationService;

    @Autowired
    @Qualifier("restTemplate")
    private RestTemplate restTemplate;

    @BeforeAll
    static void createSchemaAndTopics() throws Exception {
        // One partition, so records are consumed in the order they were published.
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(TOPIC, 1, (short) 1),
                    new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1))).all().get();
        }

        try (Connection connection = POSTGRES.createConnection("")) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/schema.sql"));
        }
    }

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM newtable");
        flushRedis();
    }

    @Test
    void duplicateDeliveryProducesOneRow() {
        TradeData candle = candle(SYMBOL, HOUR_START, "50", "60", "40", "55", "1.5", 10);

        producer.send(candle);
        producer.send(candle);
        awaitConsumed();

        assertThat(countRows(SYMBOL, HOUR_START)).isEqualTo(1);
    }

    @Test
    void correctedCandleReplacesStoredValues() {
        producer.send(candle(SYMBOL, HOUR_START, "50", "50", "50", "50", "1", 1));
        awaitRow(SYMBOL, HOUR_START);

        producer.send(candle(SYMBOL, HOUR_START, "999.99", "999.99", "999.99", "999.99", "9", 9));
        awaitConsumed();

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT open_price, volume, nums_of_trade FROM newtable WHERE symbol = ? AND open_time = ?",
                SYMBOL, HOUR_START);

        assertThat((BigDecimal) stored.get("open_price")).isEqualByComparingTo("999.99");
        assertThat((BigDecimal) stored.get("volume")).isEqualByComparingTo("9");
        assertThat(((Number) stored.get("nums_of_trade")).longValue()).isEqualTo(9L);
        assertThat(countRows(SYMBOL, HOUR_START)).isEqualTo(1);
    }

    @Test
    void hourlyAggregateIsIndependentOfDeliveryOrder() throws Exception {
        // Published out of time order on purpose.
        producer.send(candle(SYMBOL, HOUR_START + 2 * MINUTE, "12", "20", "11", "18", "3", 3));
        producer.send(candle(SYMBOL, HOUR_START, "10", "15", "9", "14", "1", 1));
        producer.send(candle(SYMBOL, HOUR_START + MINUTE, "14", "30", "13", "12", "2", 2));
        awaitConsumed();

        List<AggregatedTradeData> rows = aggregationService.getAggregated(
                SYMBOL, HOUR_START, HOUR_END, AggregationPeriod.HOURLY);

        assertThat(rows).hasSize(1);
        AggregatedTradeData hour = rows.get(0);
        assertThat(hour.getOpenPrice()).isEqualByComparingTo("10");   // earliest minute
        assertThat(hour.getClosePrice()).isEqualByComparingTo("18");  // latest minute
        assertThat(hour.getHighPrice()).isEqualByComparingTo("30");
        assertThat(hour.getLowPrice()).isEqualByComparingTo("9");
        assertThat(hour.getVolume()).isEqualByComparingTo("6");
        assertThat(hour.getNumsOfTrade()).isEqualTo(6L);
        assertThat(hour.getCandleCount()).isEqualTo(3L);
        assertThat(hour.getExpectedCandleCount()).isEqualTo(60L);
        assertThat(hour.isComplete()).isFalse();
    }

    /**
     * Found by reconciling real 2024 data: the exchange fills a minute without trades with the
     * previous close, so an empty first minute would leak the previous bucket's price into open,
     * high or low. The exchange's own candles take open, high and low from actual trades.
     */
    @Test
    void minutesWithoutTradesDoNotSetOpenHighOrLow() throws Exception {
        producer.send(candle(SYMBOL, HOUR_START, "200", "200", "200", "200", "0", 0));
        producer.send(candle(SYMBOL, HOUR_START + MINUTE, "10", "15", "9", "14", "1", 3));
        producer.send(candle(SYMBOL, HOUR_START + 2 * MINUTE, "14", "20", "13", "18", "2", 2));
        awaitConsumed();

        List<AggregatedTradeData> rows = aggregationService.getAggregated(
                SYMBOL, HOUR_START, HOUR_END, AggregationPeriod.HOURLY);

        assertThat(rows).singleElement().satisfies(hour -> {
            assertThat(hour.getOpenPrice()).isEqualByComparingTo("10");
            assertThat(hour.getHighPrice()).isEqualByComparingTo("20");
            assertThat(hour.getLowPrice()).isEqualByComparingTo("9");
            assertThat(hour.getClosePrice()).isEqualByComparingTo("18");
            assertThat(hour.getCandleCount()).isEqualTo(3L);
        });
    }

    @Test
    void hourWithEveryMinuteIsMarkedComplete() throws Exception {
        for (int minute = 0; minute < 60; minute++) {
            producer.send(candle(SYMBOL, HOUR_START + minute * MINUTE, "1", "1", "1", "1", "1", 1));
        }
        awaitConsumed();

        List<AggregatedTradeData> rows = aggregationService.getAggregated(
                SYMBOL, HOUR_START, HOUR_END, AggregationPeriod.HOURLY);

        assertThat(rows).singleElement().satisfies(hour -> {
            assertThat(hour.getCandleCount()).isEqualTo(60L);
            assertThat(hour.isComplete()).isTrue();
        });
    }

    @Test
    void closedRangeIsCachedForOneHour() throws Exception {
        producer.send(candle(SYMBOL, HOUR_START, "10", "15", "9", "14", "1", 1));
        awaitConsumed();

        aggregationService.getAggregated(SYMBOL, HOUR_START, HOUR_END, AggregationPeriod.HOURLY);

        assertThat(ttlOfOnlyAggregateKey()).isBetween(3_000L, 3_600L);
    }

    @Test
    void rangeReachingIntoTheFutureIsCachedBriefly() throws Exception {
        long now = System.currentTimeMillis();

        aggregationService.getAggregated(SYMBOL, now - 60 * MINUTE, now + 60 * MINUTE, AggregationPeriod.HOURLY);

        assertThat(ttlOfOnlyAggregateKey()).isBetween(1L, 60L);
    }

    @Test
    void ingestingNewCandleInvalidatesCachedAggregate() throws Exception {
        producer.send(candle(SYMBOL, HOUR_START, "10", "15", "9", "14", "1", 1));
        awaitConsumed();

        List<AggregatedTradeData> first =
                aggregationService.getAggregated(SYMBOL, HOUR_START, HOUR_END, AggregationPeriod.HOURLY);
        assertThat(first.get(0).getVolume()).isEqualByComparingTo("1");

        producer.send(candle(SYMBOL, HOUR_START + MINUTE, "14", "30", "13", "12", "2", 2));
        awaitConsumed();

        List<AggregatedTradeData> second =
                aggregationService.getAggregated(SYMBOL, HOUR_START, HOUR_END, AggregationPeriod.HOURLY);
        assertThat(second.get(0).getVolume())
                .as("the new candle bumped the cache version, so the aggregate is recomputed")
                .isEqualByComparingTo("3");
    }

    @Test
    void malformedRecordGoesToDeadLetterTopicWithoutBlockingConsumer() throws Exception {
        try (KafkaProducer<String, String> rawProducer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            rawProducer.send(new ProducerRecord<>(TOPIC, SYMBOL, "not json")).get();
        }

        producer.send(candle(SYMBOL, HOUR_START, "10", "15", "9", "14", "1", 1));
        awaitConsumed();
        assertThat(countRows(SYMBOL, HOUR_START))
                .as("records after the malformed one are still consumed")
                .isEqualTo(1);

        try (KafkaConsumer<String, byte[]> deadLetters = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            deadLetters.subscribe(List.of(DEAD_LETTER_TOPIC));
            List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                deadLetters.poll(Duration.ofMillis(500)).forEach(received::add);
                return !received.isEmpty();
            });

            assertThat(new String(received.get(0).value(), StandardCharsets.UTF_8)).isEqualTo("not json");
        }
    }

    @Test
    void reconciliationMatchesExchangeAndCatchesPlantedCorruption() {
        LocalDate day = LocalDate.of(2023, 11, 15);
        long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Object[]> minutes = new ArrayList<>();
        for (int minute = 0; minute < 1440; minute++) {
            long openTime = dayStart + minute * MINUTE;
            minutes.add(new Object[]{openTime, new BigDecimal(100 + minute), new BigDecimal(101 + minute),
                    new BigDecimal(99 + minute), new BigDecimal(100 + minute).add(new BigDecimal("0.5")),
                    new BigDecimal("0.5"), openTime + MINUTE - 1, 2L, SYMBOL});
        }
        jdbc.batchUpdate("""
                INSERT INTO newtable (open_time, open_price, high_price, low_price, close_price,
                                      volume, close_time, nums_of_trade, symbol)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, minutes);

        // The exchange's own daily candle for the same day: open of the first minute, close of the last.
        String exchangeDaily = "[[%d,\"100\",\"1540\",\"99\",\"1539.5\",\"720.00000000\",%d,\"0\",2880]]"
                .formatted(dayStart, dayStart + 86_400_000L - 1);

        MockRestServiceServer exchange = MockRestServiceServer.bindTo(restTemplate).build();
        exchange.expect(ExpectedCount.twice(), requestTo(containsString("interval=1d")))
                .andRespond(withSuccess(exchangeDaily, MediaType.APPLICATION_JSON));

        DailyReconciliationReport clean = reconciliationService.reconcile(SYMBOL, day, day);
        assertThat(clean.matchedDays()).isEqualTo(1);
        assertThat(clean.discrepancies()).isEmpty();

        // Plant a wrong close on the last minute of the day; the oracle must notice.
        jdbc.update("UPDATE newtable SET close_price = 0 WHERE symbol = ? AND open_time = ?",
                SYMBOL, dayStart + 1439 * MINUTE);

        DailyReconciliationReport corrupted = reconciliationService.reconcile(SYMBOL, day, day);
        assertThat(corrupted.mismatchedDays()).isEqualTo(1);
        assertThat(corrupted.discrepancies()).singleElement().satisfies(discrepancy ->
                assertThat(discrepancy.differences())
                        .extracting(DailyReconciliationReport.FieldDifference::field)
                        .containsExactly("close"));
        exchange.verify();
    }

    /**
     * The topic has a single partition and the consumer processes records in order, so once a
     * distinct sentinel record is in PostgreSQL, everything published before it has been handled.
     */
    private void awaitConsumed() {
        long sentinelTime = System.nanoTime();
        producer.send(candle("SENTINEL", sentinelTime, "1", "1", "1", "1", "1", 1));
        awaitRow("SENTINEL", sentinelTime);
    }

    private void awaitRow(String symbol, long openTime) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> countRows(symbol, openTime) == 1);
    }

    private int countRows(String symbol, long openTime) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM newtable WHERE symbol = ? AND open_time = ?",
                Integer.class, symbol, openTime);
        return count == null ? 0 : count;
    }

    private long ttlOfOnlyAggregateKey() {
        Set<String> keys = redis.keys("aggregate:v2:*");
        assertThat(keys).hasSize(1);
        Long ttlSeconds = redis.getExpire(keys.iterator().next());
        assertThat(ttlSeconds).isNotNull();
        return ttlSeconds;
    }

    private void flushRedis() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private static TradeData candle(
            String symbol, long openTime, String open, String high, String low,
            String close, String volume, long trades) {
        TradeData data = new TradeData();
        data.setSymbol(symbol);
        data.setOpenTime(openTime);
        data.setOpenPrice(new BigDecimal(open));
        data.setHighPrice(new BigDecimal(high));
        data.setLowPrice(new BigDecimal(low));
        data.setClosePrice(new BigDecimal(close));
        data.setVolume(new BigDecimal(volume));
        data.setCloseTime(openTime + MINUTE - 1);
        data.setNumsOfTrade(trades);
        data.setStartTime(openTime);
        data.setEndTime(openTime + MINUTE);
        return data;
    }
}
