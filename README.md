# Tradedate

Tradedate 是一个基于 Java 17 和 Spring Boot 3.5.11 的比特币行情数据服务。它可以从 Binance.US 拉取 1 分钟 OHLCV（K 线）数据，经 Kafka producer-consumer 链路异步写入 PostgreSQL，并通过 MyBatis 提供小时和日级聚合查询；聚合结果使用 Redis 缓存，新数据写入后自动失效。

项目还提供一个可选的 Kimi 命令行行情助手：它可以从中英文问题中识别 BTCUSDT 和日期，优先使用数据库中的完整日线数据，不完整时回退到 Binance.US，并让 Kimi 基于已验证的数据生成中文分析。

## 主要功能

- 拉取 Binance.US 的 1 分钟 BTCUSDT K 线数据；只写入已经收盘的 K 线。是否收盘按交易所的服务器时间（`/api/v3/time`）减 2 秒判断，而不是本机时钟。
- 每日对账：把数据库按 UTC 日聚合出的开高低收、成交量和成交笔数，逐字段与交易所自己的日线比对，报告一致、不一致、数据不完整和交易所缺失的天数。
- 通过 Kafka 异步入库，以 `(symbol, open_time)` 唯一索引做 upsert：重复投递不会产生重复行，修正后的 K 线会覆盖旧值。
- consumer 以批量方式消费：每次 poll（最多 `KAFKA_MAX_POLL_RECORDS` 条，默认 500）用一条多行 upsert 写入。同一批里同一根 K 线出现多次时保留最后一个版本。
- 消费失败的记录按指数退避重试，仍失败则写入死信 topic，之后可以通过管理接口查看并重放；无法反序列化或被数据库拒绝（如违反约束）的记录不重试，直接进入死信 topic。批量写入失败时会逐行重试找出那一条，同批其他记录照常写入，不会卡住分区。
- 查询 `HOURLY` 或 `DAILY` 聚合结果，每个桶附带实际/应有的分钟数和是否完整；结果通过 Redis cache-aside 缓存，新数据写入后相关缓存自动失效。
- 小时和日 K 线预聚合在 `candle_rollup` 表中，由 consumer 在写入分钟 K 线的同一个事务里更新；查询整桶直接读预聚合，只有区间两端不完整的桶才从分钟数据现算。
- 数据库结构由 Flyway 迁移脚本管理，应用启动时自动执行。
- 拉取接口和管理接口需要 `X-API-Key`；没有配置密钥时拒绝请求，而不是放行。
- 使用 Docker Compose 启动应用、单节点 Kafka 和 Redis。
- 可选启用 Kimi 交互式终端，查询已结束的单个 UTC 日期。

## 数据流

HTTP 数据链路：

```text
HTTP 请求 -> Spring Boot -> Binance.US Kline API -> Kafka producer
                                                   |
                                                   v
PostgreSQL (MyBatis) <- Kafka consumer <- trade-data topic
        |
        +-> 小时/日聚合查询 -> Redis cache-aside
```

Kimi 终端链路：

```text
自然语言问题 -> Kimi 提取 BTCUSDT/日期
                         |
                         v
完整的数据库分钟数据 -> 日线聚合 ----+
数据库不可用或数据不完整 -> Binance.US 日线
                                      |
                                      v
                              Kimi 中文行情分析
```

## 前置条件

- Git
- Java 17（仅本地 Maven 运行需要）
- Docker Desktop，或 Docker Engine + Docker Compose V2
- 一个可访问的 PostgreSQL 14+ 数据库
- 本机端口 `8080` 和 `9092` 未被占用
- 能够访问 Binance.US；使用行情终端时还需 Moonshot API Key 和 Kimi API 网络访问

PostgreSQL 是外部依赖；项目可以连接 Amazon RDS，也可以连接你自己准备的 PostgreSQL。如果使用 Amazon RDS，需要让 RDS 安全组的 PostgreSQL `5432` 入站规则允许当前电脑的公网 IP。不要为了省事开放给 `0.0.0.0/0`。

## 快速开始：Docker Compose

### 1. 克隆仓库并配置环境变量

Windows PowerShell：

```powershell
git clone https://github.com/chenziliang1/Tradedate.git
Set-Location Tradedate
Copy-Item .env.example .env
```

macOS/Linux：

```bash
git clone https://github.com/chenziliang1/Tradedate.git
cd Tradedate
cp .env.example .env
```

编辑本机的 `.env`，填入真实数据库连接信息：

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://YOUR_DATABASE_HOST:5432/YOUR_DATABASE
SPRING_DATASOURCE_USERNAME=YOUR_DATABASE_USERNAME
SPRING_DATASOURCE_PASSWORD=YOUR_DATABASE_PASSWORD
APP_API_KEY=YOUR_RANDOM_KEY
```

`APP_API_KEY` 是调用拉取接口和管理接口时 `X-API-Key` 请求头要带的密钥，可以用 `openssl rand -hex 32` 生成。不设置时这些接口一律拒绝（返回 `503`），不会因为忘了配置而变成公开接口。

`.env` 已被 Git 和 Docker 构建上下文忽略。不要把真实密码或 API Key 提交到仓库。

如果 PostgreSQL 安装在运行 Docker 的同一台电脑上，数据库主机不要写 `localhost`，因为容器内的 `localhost` 指向应用容器本身。Windows/macOS 通常可使用 `host.docker.internal`；Linux 请使用容器能够访问的宿主机地址。

### 2. 初始化数据库

不需要手动建表。应用启动时，Flyway 会执行 [`src/main/resources/db/migration`](src/main/resources/db/migration) 下的迁移脚本：

- `V1__trade_data.sql`：分钟 K 线表 `newtable` 和 `(symbol, open_time)` 唯一索引。该索引是 Kafka 重复投递时 `ON CONFLICT` 幂等写入所必需的。
- `V2__candle_rollups.sql`：小时和日预聚合表 `candle_rollup`，并用已有的分钟数据回填。

引入 Flyway 之前就已经建好表的数据库（有表、没有 Flyway 历史表）会被记为版本 0；`V1` 全部使用 `IF NOT EXISTS`，在这种数据库上不做任何改动，之后的迁移照常执行。数据库用户需要有建表权限。

### 3. 构建并启动

```bash
docker compose config --quiet
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

首次构建需要从 Docker Hub 和 Maven Central 下载依赖。应用还需要能够访问 Binance.US 和 PostgreSQL。默认的 Compose 配置启用 HTTP 服务，不启用交互式 Kimi 终端。

### 4. 验证 HTTP API

所有时间参数均为 Unix epoch 毫秒。

| 方法和路径 | 用途 |
| --- | --- |
| `GET /messages` | 检查应用是否响应 |
| `POST /api/load/{symbol}/{startTime}/{endTime}` | 拉取指定区间的 1 分钟 K 线并发送到 Kafka；需要 `X-API-Key` 请求头 |
| `GET /api/aggregates/{period}/{symbol}/{startTime}/{endTime}` | 查询聚合数据；`period` 为 `HOURLY` 或 `DAILY` |
| `GET /api/admin/dead-letters?limit=50` | 列出死信 topic 中还没处理的记录：失败原因、原始 topic 和 offset、已重放次数、能否重放；需要 `X-API-Key` |
| `POST /api/admin/dead-letters/replay?limit=100` | 把还没处理的死信重新发回行情 topic；需要 `X-API-Key` |
| `GET /api/reconciliation/daily/{symbol}/{from}/{to}` | 对账；`from`、`to` 为 `YYYY-MM-DD` 格式的 UTC 日期（含两端），最多 366 天，且必须是已经结束的日期 |

PowerShell 示例：

```powershell
Invoke-RestMethod "http://localhost:8080/messages"

$start = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToUnixTimeMilliseconds()
$end = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$headers = @{ "X-API-Key" = "YOUR_RANDOM_KEY" }
Invoke-RestMethod -Method Post -Headers $headers "http://localhost:8080/api/load/BTCUSDT/$start/$end"
Invoke-RestMethod "http://localhost:8080/api/aggregates/HOURLY/BTCUSDT/$start/$end"
```

拉取接口的行为：

- 拉取会写数据并调用交易所，所以是 `POST`，并且必须带正确的 `X-API-Key`，否则返回 `401`。查询类接口（聚合、对账、健康检查）不需要密钥。
- `symbol` 只允许 2 到 20 位字母或数字；时间区间必须满足 `startTime < endTime`，且不超过 `BINANCE_MAX_RANGE`（默认 366 天）。不满足时返回 `400`。
- 对 Binance.US 的请求最多并发 `BINANCE_MAX_CONCURRENT_REQUESTS` 个；遇到 `429`、`5xx` 或网络错误会退避重试（优先使用 `Retry-After`）。
- 返回的数量是 Kafka 已确认接收的记录数，不含未收盘而被跳过的 K 线。只要有一个批次最终失败，就返回 `502`，并说明已发送多少条、多少个批次失败；已发送的记录可以通过重新请求同一区间安全补齐。

聚合的开、高、低、收只取有成交的分钟：交易所会用上一分钟的收盘价填充没有成交的分钟，如果当天第一分钟没有成交，直接取它会把前一天的价格带进今天的开盘价。这个问题是用对账接口核对 2024 全年真实数据时发现的（修复前 366 天中有 22 天开盘价不一致，修复后 366 天全部一致）。

批量写入前后的吞吐（本机 Docker 上的 PostgreSQL 16 和 Kafka 3.9.1，从同一个 topic 消费 2024 全年 527,040 条消息，只计写库时间）：

| 版本 | 耗时 | 吞吐 |
| --- | --- | --- |
| 逐条写入 | 162.0 秒 | 约 3,250 行/秒 |
| 每次 poll 一条多行 upsert | 约 10.5 秒（两次 10.7 / 10.4） | 约 50,000 行/秒 |

加上预聚合后，consumer 在同一个事务里还要更新受影响的小时和日预聚合，吞吐下降约 25%（同样条件下两次 13.6 / 14.3 秒，未加预聚合的版本两次 10.5 / 11.5 秒）。换来的是查询（本机，Redis 缓存未命中，各 15 次取中位数，两个版本返回的结果完全相同）：

| 查询 2024 全年 | 从分钟数据现算 | 读预聚合 |
| --- | --- | --- |
| 366 个日 K 线 | 约 487 ms | 约 6.7 ms |
| 8,784 个小时 K 线 | 约 459 ms | 约 21 ms |

改成批量写入后，拉取全年数据时 consumer 已能跟上 Binance.US 的拉取速度：接口返回时（约 27 秒）数据已全部落库，之前要约 190 秒。数据库在远端（如 RDS）时每条语句多一次网络往返，实际数字会不同。

对账接口同时检查两层：

- 分钟数据按 UTC 日聚合后，和交易所自己的日线逐字段比较。只比较存满 1,440 分钟的日子；分钟数不足的记为 `INCOMPLETE`，不参与比较。数值按值比较（`1.5` 等于 `1.50000000`），成交笔数必须完全相等。不一致记为 `MISMATCH`。
- 查询实际使用的日预聚合，和同一天的分钟数据聚合结果比较。不一致记为 `ROLLUP_MISMATCH`，说明预聚合和分钟数据脱节了。

返回的 `discrepancies` 只列出有问题的日子及其不一致的字段（`stored` 是库里的值，`expected` 是交易所的值或由分钟数据算出的值）。例如核对 2024 全年：

```powershell
Invoke-RestMethod "http://localhost:8080/api/reconciliation/daily/BTCUSDT/2024-01-01/2024-12-31"
```

死信重放：

- 用一个单独的 consumer group（`<consumer group>-dlt-replay`）记录处理到哪里：之前的记录都已经重放或跳过，`GET` 只列出之后的。
- 能解析成完整 K 线的记录会带着原来的 key 发回行情 topic，所以和同一个 symbol 的其他消息在同一个分区；等 Kafka 全部确认后才提交进度，发送失败就什么都不提交，下次重新处理。
- 每次重放把 `x-replay-count` 加一。再次失败的记录会带着这个计数回到死信 topic，达到 `KAFKA_DLT_MAX_REPLAYS`（默认 3）后不再重放，避免一条坏消息无限循环。
- 无法解析的记录（例如反序列化失败的原始字节）和超过次数的记录会被跳过：在返回结果里列出原因，之后不再出现在 `GET` 中，但仍保留在死信 topic 里，直到 Kafka 的保留期结束。

聚合接口每个桶的 `candleCount` 是实际的分钟数，`expectedCandleCount` 是该桶在请求区间内应有的分钟数，`complete` 只有在两者相等且桶已经结束时为 `true`。完全没有数据的桶不会出现在结果中。

拉取接口返回的数量表示已发送到 Kafka 的记录数；最终是否落库应通过应用日志或 PostgreSQL 查询确认：

```sql
SELECT *
FROM newtable
WHERE symbol = 'BTCUSDT'
ORDER BY open_time DESC
LIMIT 10;
```

### 5. 停止服务

```bash
docker compose down
```

此 Compose 配置面向本地开发和演示：Redis 只用作可重建缓存；Kafka 是单节点、无认证且没有持久化 volume，删除容器后消息和 offset 可能丢失。业务数据保存在外部 PostgreSQL 中。

## 使用 Kimi 行情终端

终端模式目前只支持 `BTCUSDT`，并且每次问题必须对应一个已经结束的 UTC 日期。相对日期（如“昨天”“前天”）也按 UTC 解释，问题长度不能超过 2000 个字符。

### 1. 添加可选配置

在本机 `.env` 中添加：

```dotenv
MOONSHOT_API_KEY=YOUR_MOONSHOT_API_KEY
KIMI_API_URL=https://api.moonshot.ai/v1/chat/completions
KIMI_MODEL=kimi-k3
TERMINAL_CHAT_ENABLED=true
BINANCE_API_BASE_URL=https://api.binance.us
```

除 `MOONSHOT_API_KEY` 和 `TERMINAL_CHAT_ENABLED=true` 外，其余配置已有上述默认值。终端问题会发送给 Moonshot/Kimi，请不要在问题中输入密码、密钥或其他敏感信息。

### 2. 启动交互式应用

常规的 `docker compose up -d` 不会把终端配置传入应用容器，也不适合交互式输入。使用下面的一次性容器命令；它会构建应用、启动依赖、读取 `.env`，并在退出后删除应用容器：

```bash
docker compose run --rm --build --env-from-file .env -e TERMINAL_CHAT_ENABLED=true app
```

如果希望直接在本机运行，也可以先启动所需依赖，再从项目根目录执行 `./mvnw spring-boot:run`；Windows 使用 `.\mvnw.cmd spring-boot:run`，并确保当前进程可以读取 `.env` 中的配置。

启动后可输入：

```text
查询 2024 年 1 月 2 日比特币的开高低收，并分析波动
```

输入 `exit`、`quit` 或 `退出` 可退出问答模式；Spring Boot 仍会继续运行，按 `Ctrl+C` 停止应用。

查询时，应用只有在数据库恰好包含该 UTC 日完整的 1440 条分钟数据时才使用数据库日聚合，否则回退到 Binance.US 日线接口；回退结果仅用于本次查询，不会自动写回数据库。Kimi 会收到原始问题，并只根据应用提供的已验证行情生成分析；结果仅供参考，不构成投资建议。

## 配置参考

| 变量 | 必需 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `SPRING_DATASOURCE_URL` | 是 | 无 | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | 是 | 无 | PostgreSQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 是 | 无 | PostgreSQL 密码 |
| `KAFKA_BOOTSTRAP_SERVERS` | 否 | `localhost:9092` | Kafka broker；Compose 内使用 `kafka:29092` |
| `KAFKA_TRADE_DATA_TOPIC` | 否 | `trade-data` | 行情 topic |
| `KAFKA_CONSUMER_GROUP_ID` | 否 | `demo-trade-data-consumer` | Kafka consumer group |
| `KAFKA_TRADE_DATA_DLT_TOPIC` | 否 | `<行情 topic>.DLT` | 死信 topic |
| `KAFKA_MAX_POLL_RECORDS` | 否 | `500` | 每次 poll 的最大条数，也是一条多行 upsert 的最大行数 |
| `KAFKA_RETRY_MAX_RETRIES` | 否 | `3` | 写入失败后的重试次数，之后进入死信 topic |
| `KAFKA_RETRY_INITIAL_INTERVAL` | 否 | `PT1S` | 第一次重试的等待时间，之后每次翻倍 |
| `KAFKA_DLT_MAX_REPLAYS` | 否 | `3` | 一条死信最多被重放几次 |
| `REDIS_HOST` | 否 | `localhost` | Redis 主机；Compose 内使用 `redis` |
| `REDIS_PORT` | 否 | `6379` | Redis 端口 |
| `BINANCE_API_BASE_URL` | 否 | `https://api.binance.us` | 拉取分钟 K 线和日线回退查询使用的 Binance.US API 根地址 |
| `BINANCE_MAX_RANGE` | 否 | `P366D` | 单次拉取允许的最大时间区间 |
| `BINANCE_MAX_CONCURRENT_REQUESTS` | 否 | `4` | 对 Binance.US 的最大并发请求数 |
| `BINANCE_MAX_ATTEMPTS` | 否 | `3` | 每个批次的最大请求次数（含第一次） |
| `BINANCE_RETRY_BACKOFF` | 否 | `PT1S` | 重试的初始等待时间，之后每次翻倍 |
| `AGGREGATE_CACHE_CLOSED_TTL` | 否 | `PT1H` | 区间已经结束的聚合结果缓存时间 |
| `AGGREGATE_CACHE_OPEN_TTL` | 否 | `PT1M` | 区间延伸到当前时间之后的聚合结果缓存时间 |
| `APP_API_KEY` | 调用拉取和管理接口时 | 空 | `X-API-Key` 请求头要带的密钥；为空时这些接口返回 `503` |
| `MOONSHOT_API_KEY` | 仅终端 | 空 | Moonshot API Key |
| `KIMI_API_URL` | 否 | `https://api.moonshot.ai/v1/chat/completions` | Kimi Chat Completions 地址 |
| `KIMI_MODEL` | 否 | `kimi-k3` | Kimi 模型名 |
| `TERMINAL_CHAT_ENABLED` | 否 | `false` | 是否启动交互式行情终端 |

## 本地测试

Windows：

```powershell
.\mvnw.cmd test
```

macOS/Linux：

```bash
./mvnw test
```

测试分两类：

- 单元测试：Binance 响应映射、按交易所时钟过滤未收盘 K 线、重试与输入校验、Kafka 发送失败、对账的分类与校验、数据加载接口、Kimi 请求/响应和数据库完整性条件。
- 集成测试（`PipelineIntegrationTest`）：用 Testcontainers 启动真实的 Kafka、PostgreSQL 和 Redis，数据库结构由 Flyway 迁移创建，覆盖预聚合在修正和乱序投递后仍与分钟数据一致、不对齐的查询区间只统计区间内的分钟、重复投递幂等、修正 K 线覆盖旧值、乱序投递下的小时聚合、桶完整性、缓存 TTL、新数据写入后缓存失效、畸形消息进入死信 topic 且不阻塞后续消息、被数据库拒绝的一行进入死信 topic 而同批其他行照常写入、同一批里的修正版覆盖原版，对账能同时发现被故意改坏的一根分钟 K 线和因此脱节的预聚合，以及死信的查看和重放（可重放的写入成功，无法解析的和超过次数的被跳过，处理过的不再出现）。

集成测试需要本机运行 Docker；没有 Docker 时会被跳过而不是失败。尚未覆盖交互式终端循环和完整的 Kimi 回退链路。

每次 push 和 pull request 都会通过 GitHub Actions（`.github/workflows/ci.yml`）运行全部测试。

## 持续交付与部署

GitHub Actions（`.github/workflows/ci.yml`）在每次 push 时：

1. 运行全部测试；
2. 测试通过后构建 Docker 镜像，推送到 `ghcr.io/chenziliang1/tradedate`，标签为 `sha-<commit>` 和分支名；
3. 可选：通过 AWS Systems Manager 让 EC2 拉取这个 commit 的镜像并重启，等待 `/actuator/health` 通过（PostgreSQL 和 Redis 都可达）才算成功，否则任务失败并打印应用日志。

第 3 步默认关闭，需要一次性配置 AWS（OIDC 角色、EC2 上的 Docker 和数据库配置），步骤见 [deploy/README.md](deploy/README.md)。部署不使用 SSH，GitHub 上不保存 AWS 密钥或数据库密码。

## Jenkins 说明

这个仓库目前没有 Jenkinsfile。此前完成的 Jenkins 练习是独立的 Maven/Hello World 部署实验，目标是本地私有网络中的 CentOS 虚拟机，并不是 Tradedate 在 AWS 上的生产部署链路。当前仓库可复现的部署方式以本页的 Docker Compose 流程为准。
