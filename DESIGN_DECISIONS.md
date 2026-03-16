# Design Decisions --- Mutual Fund Analytics Platform

## Overview

This document explains the key design choices made while building the
Mutual Fund Analytics backend.

The goal of the system is to fetch Net Asset Value (NAV) data from the
public API provided by mfapi.in, store the historical data locally,
compute analytics such as rolling returns and drawdowns, and expose the
results through REST APIs.

The platform also needs to respect the API rate limits imposed by
mfapi.in while still allowing the system to process multiple funds
efficiently.

The design focuses on the following aspects:

-   Respecting strict API rate limits\
-   Reliable data storage and recovery\
-   Fast analytics queries\
-   Ability to evolve the system in the future


The sections below explain the reasoning behind the important
architectural choices.

------------------------------------------------------------------------

# 1. Rate Limiting Strategy

The external API used by the platform enforces three simultaneous
limits:

-   2 requests per second\
-   50 requests per minute\
-   300 requests per hour

If these limits are exceeded, the API may temporarily block the client.
Because of this, the system must guarantee that requests are always sent
within the allowed limits.

Several approaches were considered before selecting the final algorithm.

### Token Bucket

The token bucket algorithm allows bursts of traffic as long as tokens
are available in the bucket. While this works well in many systems, it
can still produce short bursts near time window boundaries.

For example, requests could be sent just before and just after the
bucket refill. This can effectively double the allowed request rate for
a very short moment.

Since the mfapi API enforces strict limits, this approach was not ideal.

### Fixed Window Counter

The fixed window approach counts requests in fixed time intervals such
as one second or one minute.

However, this method has a similar boundary issue. A client could send
requests at the end of one window and again immediately at the beginning
of the next window, effectively exceeding the intended rate.

For example:

-   2 requests at 00:59.999\
-   2 requests at 01:00.001

This results in 4 requests within a few milliseconds, violating the 2
requests/second limit.

### Sliding Window Counter (Chosen Approach)

The sliding window method tracks the exact timestamp of each request and
checks how many requests occurred in the last time window.

For example, when a new request arrives, the system checks:

-   How many requests happened in the last 1 second\
-   How many happened in the last 1 minute\
-   How many happened in the last 1 hour

If any of these limits would be exceeded, the system waits before
sending the request.

This approach ensures that the rate limits are never violated,
regardless of timing boundaries.

------------------------------------------------------------------------

# 2. Coordinating Multiple Rate Limits

The API imposes three different limits simultaneously.\
A request must satisfy all three limits at the same time.

Because of this, the rate limiter checks the following before allowing a
request:

-   Requests in the last second\
-   Requests in the last minute\
-   Requests in the last hour

If any of the limits has already been reached, the system calculates how
long it should wait before the next request can be safely sent.

In practice, this means the system may wait a small amount of time
before sending a request. This small delay ensures that the platform
always stays within the allowed quota.

This coordination allows multiple worker threads to safely use the same
limiter without accidentally sending too many requests.

------------------------------------------------------------------------

# 3. Data Backfill Strategy

When the platform starts for the first time, it must download historical
NAV data for all funds.

Fortunately, the mfapi API returns the entire NAV history for a fund in
a single request. This means the system only needs one request per fund.

For the assignment scenario, we track 10 funds, so the initial backfill
requires only 10 API calls.

Because the API allows 2 requests per second, the complete data download
finishes in only a few seconds.

### Worker Pool

To speed up processing, the system uses a small worker pool.

Each worker:

1.  Requests data for a fund\
2.  Stores the NAV history\
3.  Computes analytics

Before making the API request, the worker asks the rate limiter for
permission. This ensures that even with multiple workers, the API limits
are always respected.

### Incremental Synchronization

NAV values change daily. Instead of fetching data continuously, the
platform periodically synchronizes with the API.

If a fund was recently synced, the system skips it. This prevents
unnecessary API calls and reduces load on the external service.

### Crash Recovery

The system keeps track of synchronization progress using a small state
table.

Each fund has a status such as:

-   pending\
-   in progress\
-   completed\
-   failed

If the system crashes during a sync, the next startup simply retries the
unfinished funds. Since the storage process is idempotent, repeating the
operation does not create duplicates.

------------------------------------------------------------------------

# 4. Data Storage Design

The platform needs to store historical NAV data for each fund.

Several storage options were considered.

### In-Memory Storage

Storing everything in memory would be simple, but it would lose all data
if the application restarts.

The system would need to re-download the entire history every time it
starts, which is inefficient.

### Traditional Databases (PostgreSQL / MySQL)

These databases are powerful and scalable, but they require additional
infrastructure such as database servers and configuration.

For a system tracking only a small number of funds, this setup would add
unnecessary complexity.

### SQLite (Chosen Approach)

SQLite was selected because it offers several advantages:

-   No separate server required\
-   Data stored in a single file\
-   Fully ACID compliant\
-   Fast enough for the dataset size

The dataset is relatively small. Even with thousands of NAV records per
fund, the total number of rows remains manageable.

If the platform later expands to thousands of funds, the storage layer
can easily be switched to PostgreSQL with minimal code changes.

------------------------------------------------------------------------

# 5. Pre-computation vs On-Demand Analytics

The platform computes metrics such as:

-   Rolling returns\
-   CAGR distribution\
-   Maximum drawdown

These calculations involve processing thousands of historical NAV
values.

If the system calculated these metrics every time an API request
arrived, each request could take hundreds of milliseconds, which would
lead to slow response times.

### Chosen Strategy: Pre-compute Analytics

Instead of calculating analytics on demand, the system computes them
during the synchronization process.

The results are stored in the database and cached in memory.

This approach has several benefits:

-   API responses are extremely fast\
-   Expensive calculations are performed only once per day\
-   System load remains low even under heavy traffic

Since NAV data typically updates once per trading day, analytics only
need to be recomputed when new data arrives.

------------------------------------------------------------------------

# 6. Handling Funds With Limited History

Some funds may not have enough historical data to compute long-term
metrics.

For example:

-   A 10-year window requires around 2500 trading days.\
-   A newly launched fund may only have a few years of data.

Instead of returning incorrect results, the system simply skips
analytics windows that do not have enough historical data.

The API response clearly shows which data ranges are available. This
allows clients to understand why certain metrics are missing.

For ranking endpoints, funds without enough history are excluded from
that ranking.

------------------------------------------------------------------------

# 7. Caching Strategy

Analytics results are stored in a small in-memory cache.

The cache keeps recently accessed analytics results for a limited period
of time.

Benefits of this approach include:

-   Faster responses for repeated requests\
-   Reduced database queries\
-   Lower CPU usage

The cache is automatically refreshed when new analytics are computed
during synchronization.

Some endpoints are intentionally not cached, such as raw NAV history and
synchronization status. These queries are already fast and caching them
would add unnecessary complexity.

------------------------------------------------------------------------

# 8. Failure Handling

The system is designed to continue operating even when individual
failures occur.

### External API Failures

If the external API returns an error or times out, the system logs the
error and marks that fund as failed. Other funds continue processing
normally.

The next synchronization attempt will retry the failed fund.

### Data Parsing Issues

Occasionally, the API may return malformed or missing data. In such
cases, the invalid record is skipped while the rest of the response is
processed normally.

### Database Errors

If the database encounters a write failure, the transaction is rolled
back. The fund is marked as failed so that it can be retried later.

### Rate Limit Violations

If the API unexpectedly returns a rate-limit error, the system pauses
further requests until the quota window resets.

Because of the rate limiter, this situation should rarely occur.

------------------------------------------------------------------------

# 9. Future Improvements

Although the system is designed for a small number of funds, it can
easily scale if needed.

Possible improvements include:

-   Migrating from SQLite to PostgreSQL for larger datasets\
-   Using Redis for distributed caching\
-   Adding message queues to process sync jobs asynchronously\
-   Supporting advanced analytics queries such as event-based
    performance comparisons

Because the analytics engine works on generic NAV datasets, new
analytical features can be added without major architectural changes.
