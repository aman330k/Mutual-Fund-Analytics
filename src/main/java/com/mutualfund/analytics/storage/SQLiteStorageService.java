package com.mutualfund.analytics.storage;

import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.Fund;
import com.mutualfund.analytics.model.NAVRecord;
import com.mutualfund.analytics.model.SyncState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class SQLiteStorageService implements StorageService {

    private final JdbcTemplate jdbc;

    /**
     * Create all tables on first run if they don't exist, then apply additive
     * column migrations so existing databases are upgraded automatically.
     */
    @PostConstruct
    public void initSchema() {
        log.info("Initializing database schema...");

        // funds table
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS funds (
                code         TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                amc          TEXT NOT NULL,
                category     TEXT NOT NULL,
                inception_date TEXT,
                created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """);

        // nav_history: one row per fund per trading day
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS nav_history (
                fund_code   TEXT NOT NULL,
                date        TEXT NOT NULL,
                nav         REAL NOT NULL,
                PRIMARY KEY (fund_code, date),
                FOREIGN KEY (fund_code) REFERENCES funds(code)
            )
            """);

        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_nav_fund_date ON nav_history(fund_code, date)
            """);

        // analytics: pre-computed results per fund per window
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS analytics (
                fund_code              TEXT NOT NULL,
                window                 TEXT NOT NULL,
                rolling_min            REAL,
                rolling_max            REAL,
                rolling_median         REAL,
                rolling_p25            REAL,
                rolling_p75            REAL,
                max_drawdown           REAL,
                cagr_min               REAL,
                cagr_max               REAL,
                cagr_median            REAL,
                rolling_periods_analyzed INTEGER,
                computed_at            TIMESTAMP,
                PRIMARY KEY (fund_code, window),
                FOREIGN KEY (fund_code) REFERENCES funds(code)
            )
            """);

        // Additive migrations: add data_availability columns if missing.
        // SQLite does not support IF NOT EXISTS on ALTER TABLE ADD COLUMN,
        // so we attempt each migration and silently ignore "duplicate column" errors.
        tryAddColumn("analytics", "start_date",      "TEXT");
        tryAddColumn("analytics", "end_date",         "TEXT");
        tryAddColumn("analytics", "total_days",       "INTEGER DEFAULT 0");
        tryAddColumn("analytics", "nav_data_points",  "INTEGER DEFAULT 0");

        // sync_state: pipeline checkpoint per fund
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sync_state (
                fund_code       TEXT PRIMARY KEY,
                last_synced_date TEXT,
                status          TEXT NOT NULL DEFAULT 'pending',
                updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (fund_code) REFERENCES funds(code)
            )
            """);

        log.info("Database schema ready");
    }

    /**
     * Attempts to add a column to a table.
     * If the column already exists, SQLite raises an error which we silently consume.
     * This makes schema migrations idempotent.
     */
    private void tryAddColumn(String table, String column, String type) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("Added column {}.{}", table, column);
        } catch (Exception e) {
            // "duplicate column name" → already present, nothing to do
            log.debug("Column {}.{} already exists (skipping migration)", table, column);
        }
    }

    //  Fund operations
    @Override
    public void upsertFund(Fund fund) {
        jdbc.update("""
            INSERT INTO funds(code, name, amc, category, inception_date)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(code) DO UPDATE SET
                name=excluded.name, amc=excluded.amc, category=excluded.category
            """,
            fund.getCode(), fund.getName(), fund.getAmc(),
            fund.getCategory(), fund.getInceptionDate());
    }

    @Override
    public List<Fund> getAllFunds() {
        return jdbc.query("SELECT * FROM funds ORDER BY code", FUND_MAPPER);
    }

    /**
     * Returns funds filtered by optional category and/or AMC using an
     * in-memory Java-side filter applied after fetching all funds.
     */
    @Override
    public List<Fund> getAllFundsFiltered(String category, String amc) {
        List<Fund> all = getAllFunds();
        if (category == null && amc == null) {
            return all;
        }

        final String catLower = (category != null && !category.isBlank())
            ? category.toLowerCase() : null;
        final String amcLower = (amc != null && !amc.isBlank())
            ? amc.toLowerCase() : null;

        return all.stream()
            .filter(f -> {
                boolean catOk = catLower == null
                    || (f.getCategory() != null && f.getCategory().toLowerCase().contains(catLower));
                boolean amcOk = amcLower == null
                    || (f.getAmc() != null && f.getAmc().toLowerCase().contains(amcLower));
                return catOk && amcOk;
            })
            .toList();
    }

    @Override
    public Optional<Fund> getFundByCode(String code) {
        List<Fund> funds = jdbc.query(
            "SELECT * FROM funds WHERE code = ?", FUND_MAPPER, code);
        return funds.isEmpty() ? Optional.empty() : Optional.of(funds.get(0));
    }

    //NAV operations

    @Override
    public void upsertNAVBatch(List<NAVRecord> records) {
        if (records.isEmpty()) return;

        // Use batch update for performance (single round-trip for all rows)
        List<Object[]> params = records.stream()
            .map(r -> new Object[]{ r.getFundCode(), r.getDate(), r.getNav() })
            .toList();

        jdbc.batchUpdate("""
            INSERT INTO nav_history(fund_code, date, nav) VALUES (?, ?, ?)
            ON CONFLICT(fund_code, date) DO UPDATE SET nav=excluded.nav
            """, params);
    }

    @Override
    public List<NAVRecord> getNAVHistory(String fundCode) {
        return jdbc.query(
            "SELECT fund_code, date, nav FROM nav_history WHERE fund_code=? ORDER BY date",
            NAV_MAPPER, fundCode);
    }

    @Override
    public Optional<String> getLatestNAVDate(String fundCode) {
        List<String> results = jdbc.queryForList(
            "SELECT MAX(date) FROM nav_history WHERE fund_code=?", String.class, fundCode);
        String val = results.isEmpty() ? null : results.get(0);
        return Optional.ofNullable(val);
    }

    /**
     * Returns the most recent NAV record for a fund (latest date + its NAV value).
     * Used to populate current_nav and last_updated in ranking and fund-detail responses.
     */
    @Override
    public Optional<NAVRecord> getLatestNAV(String fundCode) {
        List<NAVRecord> results = jdbc.query(
            "SELECT fund_code, date, nav FROM nav_history WHERE fund_code=? ORDER BY date DESC LIMIT 1",
            NAV_MAPPER, fundCode);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public int getNavCount(String fundCode) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM nav_history WHERE fund_code=?", Integer.class, fundCode);
        return count == null ? 0 : count;
    }

    //Analytics operations
    @Override
    public void upsertAnalytics(AnalyticsResult r) {
        jdbc.update("""
            INSERT INTO analytics(fund_code, window, rolling_min, rolling_max,
                rolling_median, rolling_p25, rolling_p75, max_drawdown,
                cagr_min, cagr_max, cagr_median, rolling_periods_analyzed, computed_at,
                start_date, end_date, total_days, nav_data_points)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(fund_code, window) DO UPDATE SET
                rolling_min=excluded.rolling_min, rolling_max=excluded.rolling_max,
                rolling_median=excluded.rolling_median, rolling_p25=excluded.rolling_p25,
                rolling_p75=excluded.rolling_p75, max_drawdown=excluded.max_drawdown,
                cagr_min=excluded.cagr_min, cagr_max=excluded.cagr_max,
                cagr_median=excluded.cagr_median,
                rolling_periods_analyzed=excluded.rolling_periods_analyzed,
                computed_at=excluded.computed_at,
                start_date=excluded.start_date, end_date=excluded.end_date,
                total_days=excluded.total_days, nav_data_points=excluded.nav_data_points
            """,
            r.getFundCode(), r.getWindow(), r.getRollingMin(), r.getRollingMax(),
            r.getRollingMedian(), r.getRollingP25(), r.getRollingP75(), r.getMaxDrawdown(),
            r.getCagrMin(), r.getCagrMax(), r.getCagrMedian(),
            r.getRollingPeriodsAnalyzed(), r.getComputedAt(),
            r.getStartDate(), r.getEndDate(), r.getTotalDays(), r.getNavDataPoints());
    }

    @Override
    public Optional<AnalyticsResult> getAnalytics(String fundCode, String window) {
        List<AnalyticsResult> results = jdbc.query(
            "SELECT * FROM analytics WHERE fund_code=? AND window=?",
            ANALYTICS_MAPPER, fundCode, window);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<AnalyticsResult> getAllAnalyticsForFund(String fundCode) {
        return jdbc.query(
            "SELECT * FROM analytics WHERE fund_code=? ORDER BY window",
            ANALYTICS_MAPPER, fundCode);
    }

    /**
     * Returns one analytics row per fund for the given window.
     * More efficient than N individual queries when building a ranking response.
     */
    @Override
    public List<AnalyticsResult> getAllAnalyticsForWindow(String window) {
        return jdbc.query(
            "SELECT * FROM analytics WHERE window=? ORDER BY cagr_median DESC",
            ANALYTICS_MAPPER, window);
    }

    //  Sync state operations
    @Override
    public void upsertSyncState(SyncState state) {
        jdbc.update("""
            INSERT INTO sync_state(fund_code, last_synced_date, status, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(fund_code) DO UPDATE SET
                last_synced_date=excluded.last_synced_date,
                status=excluded.status,
                updated_at=excluded.updated_at
            """,
            state.getFundCode(), state.getLastSyncedDate(),
            state.getStatus(), state.getUpdatedAt());
    }

    @Override
    public Optional<SyncState> getSyncState(String fundCode) {
        List<SyncState> results = jdbc.query(
            "SELECT * FROM sync_state WHERE fund_code=?", SYNC_MAPPER, fundCode);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<SyncState> getAllSyncStates() {
        return jdbc.query("SELECT * FROM sync_state ORDER BY fund_code", SYNC_MAPPER);
    }

    private static final RowMapper<Fund> FUND_MAPPER = (rs, row) -> Fund.builder()
        .code(rs.getString("code"))
        .name(rs.getString("name"))
        .amc(rs.getString("amc"))
        .category(rs.getString("category"))
        .inceptionDate(rs.getString("inception_date"))
        .build();

    private static final RowMapper<NAVRecord> NAV_MAPPER = (rs, row) -> NAVRecord.builder()
        .fundCode(rs.getString("fund_code"))
        .date(rs.getString("date"))
        .nav(rs.getDouble("nav"))
        .build();

    private static final RowMapper<AnalyticsResult> ANALYTICS_MAPPER = (rs, row) ->
        AnalyticsResult.builder()
            .fundCode(rs.getString("fund_code"))
            .window(rs.getString("window"))
            .rollingMin(rs.getDouble("rolling_min"))
            .rollingMax(rs.getDouble("rolling_max"))
            .rollingMedian(rs.getDouble("rolling_median"))
            .rollingP25(rs.getDouble("rolling_p25"))
            .rollingP75(rs.getDouble("rolling_p75"))
            .maxDrawdown(rs.getDouble("max_drawdown"))
            .cagrMin(rs.getDouble("cagr_min"))
            .cagrMax(rs.getDouble("cagr_max"))
            .cagrMedian(rs.getDouble("cagr_median"))
            .rollingPeriodsAnalyzed(rs.getInt("rolling_periods_analyzed"))
            .computedAt(toLocalDateTime(rs, "computed_at"))
            .startDate(rs.getString("start_date"))
            .endDate(rs.getString("end_date"))
            .totalDays(rs.getInt("total_days"))
            .navDataPoints(rs.getInt("nav_data_points"))
            .build();

    private static final RowMapper<SyncState> SYNC_MAPPER = (rs, row) -> SyncState.builder()
        .fundCode(rs.getString("fund_code"))
        .lastSyncedDate(rs.getString("last_synced_date"))
        .status(rs.getString("status"))
        .updatedAt(toLocalDateTime(rs, "updated_at"))
        .build();

    private static LocalDateTime toLocalDateTime(ResultSet rs, String col) {
        try {
            String val = rs.getString(col);
            if (val == null) return null;
            return LocalDateTime.parse(val.replace(" ", "T").substring(0, 19));
        } catch (SQLException e) {
            return null;
        }
    }
}
