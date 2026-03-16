# Mutual Fund Analytics Platform — Java / Spring Boot

Fetches live NAV data from [mfapi.in](https://api.mfapi.in), stores it in SQLite, computes rolling performance analytics (CAGR, drawdown, percentiles), and serves them via REST APIs.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | **17** (required; tested with Oracle JDK 17.0.9) |
| Maven | 3.9+ |

> **Note:** The project uses Java 17 language features and Spring Boot 3.2.  
> Java 21 also works. Java 8 / 11 will **not** compile.

### Check what you have

```bash
java -version   # must show 17 or higher
mvn -version
```

---

## Quick-Start

```bash
# Install SDKMAN (if not present for supporting multiple Java Versions)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java 17
sdk install java 17.0.9-oracle
sdk use java 17.0.9-oracle

# enter project
cd MutualFund-Analytics

# Start server
mvn spring-boot:run -DskipTests

# In a separate terminal — trigger first sync
curl -X POST http://localhost:8081/api/v1/sync/trigger

# Watch sync progress
curl http://localhost:8081/api/v1/sync/status
```


## Run the Server

```bash
cd MutualFundAnalyticsJava

# 1. Make sure Java 17 is active
java -version    # must show 17+

# 2. Compile (skip tests — tests require Java 17 JVM to run)
mvn compile -DskipTests

# 3. Start the server on port 8081
mvn spring-boot:run -DskipTests
```

Server starts on **http://localhost:8081**

---

## API Endpoints

### Funds

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/funds` | List all tracked funds |
| `GET` | `/api/v1/funds?amc=HDFC` | Filter by AMC (partial, case-insensitive) |
| `GET` | `/api/v1/funds?category=Mid+Cap` | Filter by category |
| `GET` | `/api/v1/funds?amc=HDFC&category=Small` | Combined filter |
| `GET` | `/api/v1/funds/{code}` | Fund detail + current NAV |
| `GET` | `/api/v1/funds/{code}/nav` | Full NAV history |
| `GET` | `/api/v1/funds/{code}/analytics` | Analytics for all windows |
| `GET` | `/api/v1/funds/{code}/analytics?window=3Y` | Analytics for one window (`1Y`, `3Y`, `5Y`, `10Y`) |

### Ranking

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/funds/rank?category=Mid+Cap&window=3Y` | Rank funds in a category |
| `GET` | `/api/v1/funds/rank?...&sort_by=max_drawdown` | Sort by max drawdown (default: `median_return`) |
| `GET` | `/api/v1/funds/rank?...&limit=10` | Limit results (default: 5, max: 100) |

### Sync & Operations

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/sync/trigger` | Start background NAV sync + analytics recompute |
| `GET` | `/api/v1/sync/status` | Pipeline progress + per-fund status |
| `GET` | `/api/v1/metrics` | JVM stats, pipeline counters, version |

### Legacy

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/analytics/ranking?window=3Y` | Rank all funds (no category filter) |

---

### Example calls

```bash
BASE="http://localhost:8081/api/v1"

# All funds
curl "$BASE/funds"

# Only HDFC funds
curl "$BASE/funds?amc=HDFC"

# Only Mid Cap funds
curl "$BASE/funds?category=Mid+Cap"

# Fund detail
curl "$BASE/funds/118989"

# NAV history (3000+ records)
curl "$BASE/funds/118989/nav"

# All analytics windows
curl "$BASE/funds/118989/analytics"

# 3Y rolling analytics (spec format)
curl "$BASE/funds/118989/analytics?window=3Y"

# Top 5 Mid Cap funds ranked by 3Y median CAGR
curl "$BASE/funds/rank?category=Mid+Cap&window=3Y&sort_by=median_return&limit=5"

# Top 3 Mid Cap funds ranked by least max drawdown
curl "$BASE/funds/rank?category=Mid+Cap&window=3Y&sort_by=max_drawdown&limit=3"

# Trigger sync
curl -X POST "$BASE/sync/trigger"

# Watch sync status
curl "$BASE/sync/status"

# System metrics
curl "$BASE/metrics"
```

---

## Run Tests

> Tests must be run with **Java 17+** as the active JVM.

```bash
# Ensure Java 17 is active
sdk use java 17.0.9-oracle

# Run all tests
mvn test

# Run specific test classes
mvn test -Dtest=RateLimiterTest
mvn test -Dtest=AnalyticsEngineTest
mvn test -Dtest=FundControllerTest
```

---

## Project Structure

```
MutualFundAnalyticsJava/
├── pom.xml                                     # Maven dependencies (Java 17, Spring Boot 3.2)
├── DESIGN_DECISIONS.md                         # Architecture decisions
├── data/
│   ├── mf_analytics.db                         # SQLite database (created on first run)
│   └── ratelimiter_state.json                  # Rate limiter crash-recovery state
└── src/
    ├── main/
    │   ├── java/com/mutualfund/analytics/
    │   │   ├── MutualFundAnalyticsApplication.java   # Spring Boot entry point
    │   │   ├── model/          # Fund, NAVRecord, AnalyticsResult, SyncState, FundRegistry
    │   │   ├── ratelimiter/    # Sliding-window rate limiter (2/sec · 50/min · 300/hr)
    │   │   ├── mfapi/          # HTTP client for api.mfapi.in
    │   │   ├── storage/        # SQLite persistence via Spring JdbcTemplate
    │   │   ├── analytics/      # Rolling CAGR, max drawdown, percentile engine
    │   │   ├── cache/          # In-memory TTL analytics cache (ConcurrentHashMap)
    │   │   ├── pipeline/       # Async worker pool for backfill + incremental sync
    │   │   ├── service/        # Business logic (filtering, ranking, cache-or-db)
    │   │   ├── api/            # REST controllers + global exception handler
    │   │   └── config/         # Thread pool and async configuration
    │   └── resources/
    │       └── application.properties           # Port, DB path, rate limiter config
    └── test/
        └── java/com/mutualfund/analytics/
            ├── ratelimiter/    # Rate limiter correctness + burst tests
            ├── analytics/      # CAGR, drawdown, percentile correctness tests
            └── api/            # MockMvc controller tests
```

---

## Configuration (`application.properties`)

```properties
server.port=8081

ratelimiter.per-second=2
ratelimiter.per-minute=50
ratelimiter.per-hour=300
ratelimiter.state-file=data/ratelimiter_state.json

# Pipeline
pipeline.workers=2          # concurrent sync workers
pipeline.recent-sync-hours=12

# Analytics cache TTL
cache.ttl-minutes=60
```

---

