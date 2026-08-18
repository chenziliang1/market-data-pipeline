# Tradedate

Tradedate 是一个基于 Java 17 和 Spring Boot 3.5.11 的比特币行情数据服务。它可以从 Binance.US 拉取 1 分钟 OHLCV（K 线）数据，经 Kafka producer-consumer 链路异步写入 PostgreSQL，并通过 MyBatis 提供小时和日级聚合查询；聚合结果使用 Redis 缓存 1 小时。

项目还提供一个可选的 Kimi 命令行行情助手：它可以从中英文问题中识别 BTCUSDT 和日期，优先使用数据库中的完整日线数据，不完整时回退到 Binance.US，并让 Kimi 基于已验证的数据生成中文分析。

## 主要功能

- 拉取 Binance.US 的 1 分钟 BTCUSDT K 线数据。
- 通过 Kafka 异步入库，并以 `(symbol, open_time)` 唯一索引保证重复投递时幂等。
- 查询 `HOURLY` 或 `DAILY` 聚合结果，并通过 Redis cache-aside 缓存。
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
```

`.env` 已被 Git 和 Docker 构建上下文忽略。不要把真实密码或 API Key 提交到仓库。

如果 PostgreSQL 安装在运行 Docker 的同一台电脑上，数据库主机不要写 `localhost`，因为容器内的 `localhost` 指向应用容器本身。Windows/macOS 通常可使用 `host.docker.internal`；Linux 请使用容器能够访问的宿主机地址。

### 2. 初始化数据库

对新数据库执行 [`db/schema.sql`](db/schema.sql)。可以在 DBeaver 中打开并执行，也可以使用 `psql`：

```bash
psql "host=YOUR_DATABASE_HOST port=5432 dbname=YOUR_DATABASE user=YOUR_DATABASE_USERNAME sslmode=require" -f db/schema.sql
```

脚本会创建应用使用的 `newtable` 和 `(symbol, open_time)` 唯一索引。该索引是 Kafka 重复投递时 `ON CONFLICT` 幂等写入所必需的。

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
| `GET /{symbol}/{startTime}/{endTime}` | 拉取指定区间的 1 分钟 K 线并发送到 Kafka |
| `GET /api/aggregates/{period}/{symbol}/{startTime}/{endTime}` | 查询聚合数据；`period` 为 `HOURLY` 或 `DAILY` |

PowerShell 示例：

```powershell
Invoke-RestMethod "http://localhost:8080/messages"

$start = [DateTimeOffset]::UtcNow.AddMinutes(-5).ToUnixTimeMilliseconds()
$end = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

Invoke-RestMethod "http://localhost:8080/BTCUSDT/$start/$end"
Invoke-RestMethod "http://localhost:8080/api/aggregates/HOURLY/BTCUSDT/$start/$end"
```

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
| `REDIS_HOST` | 否 | `localhost` | Redis 主机；Compose 内使用 `redis` |
| `REDIS_PORT` | 否 | `6379` | Redis 端口 |
| `BINANCE_API_BASE_URL` | 否 | `https://api.binance.us` | 日线回退查询使用的 Binance.US API 根地址 |
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

当前 9 个自动化测试覆盖 Binance 响应映射、数据加载接口、Kimi 请求/响应和数据库完整性条件；尚未覆盖真实数据库聚合、Redis、Kafka consumer、交互式终端循环和完整回退链路，也不等同于完整端到端测试。

## Jenkins 说明

这个仓库目前没有 Jenkinsfile。此前完成的 Jenkins 练习是独立的 Maven/Hello World 部署实验，目标是本地私有网络中的 CentOS 虚拟机，并不是 Tradedate 在 AWS 上的生产部署链路。当前仓库可复现的部署方式以本页的 Docker Compose 流程为准。
