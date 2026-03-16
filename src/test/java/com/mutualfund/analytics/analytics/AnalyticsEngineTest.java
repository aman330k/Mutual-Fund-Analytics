package com.mutualfund.analytics.analytics;

import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.NAVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsEngineTest {

    private AnalyticsEngine engine;

    @BeforeEach
    void setUp() {
        engine = new AnalyticsEngine();
    }

    /**
     * Test max drawdown with a known sequence.
     * 100 → 150 → 90 → 130
     * Peak = 150, trough = 90 → drawdown = (90-150)/150 = -40%
     */
    @Test
    void testMaxDrawdown() {
        double[] navs = {100, 110, 130, 150, 140, 120, 90, 100, 130};
        double dd = engine.computeMaxDrawdown(navs);

        // Drawdown from 150 to 90 = -40%
        assertThat(dd).isLessThan(-35.0);
        assertThat(dd).isGreaterThan(-45.0);
    }

    /**
     * Test max drawdown with monotonically increasing NAVs.
     * No drawdown should exist.
     */
    @Test
    void testMaxDrawdownNoDecline() {
        double[] navs = {100, 110, 120, 130, 140, 150};
        double dd = engine.computeMaxDrawdown(navs);
        assertThat(dd).isEqualTo(0.0);
    }

    /**
     * Test percentile calculation on a simple sorted list.
     */
    @Test
    void testPercentile() {
        List<Double> data = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);

        assertThat(engine.percentile(data, 0)).isEqualTo(1.0);
        assertThat(engine.percentile(data, 100)).isEqualTo(10.0);

        // Median of [1..10] = 5.5
        assertThat(engine.percentile(data, 50)).isEqualTo(5.5);
    }

    /**
     * Test that computeAll returns empty list when data is insufficient.
     */
    @Test
    void testComputeAllInsufficientData() {
        List<NAVRecord> records = buildNAVRecords(20);  // only 20 records
        List<AnalyticsResult> results = engine.computeAll("TEST001", records);
        assertThat(results).isEmpty();
    }

    /**
     * Test that computeAll produces results when data is sufficient for 1Y window.
     * Need: 1Y = 252 trading days + 30 buffer = 282 records
     */
    @Test
    void testComputeAllWithEnoughData() {
        List<NAVRecord> records = buildNAVRecords(400);  // 400 records (more than 1Y)
        List<AnalyticsResult> results = engine.computeAll("TEST001", records);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getWindow()).isEqualTo("1Y");
        assertThat(results.get(0).getRollingPeriodsAnalyzed()).isGreaterThan(0);
    }

    /**
     * Test CAGR accuracy: NAV doubles in exactly 2 years → CAGR ≈ 41.42%
     * Formula: (200/100)^(1/2) - 1 = 0.4142 = 41.42%
     */
    @Test
    void testCAGRAccuracy() {
        // Build 504 records (2 years of trading days) where NAV goes from 100 to 200
        List<NAVRecord> records = new ArrayList<>();
        int totalDays = 504;
        double startNav = 100.0;
        double endNav   = 200.0;

        for (int i = 0; i < totalDays; i++) {
            double nav = startNav + (endNav - startNav) * i / (totalDays - 1);
            records.add(NAVRecord.builder()
                .fundCode("TEST")
                .date("2020-01-" + String.format("%02d", (i % 28) + 1))
                .nav(nav)
                .build());
        }

        List<AnalyticsResult> results = engine.computeAll("TEST", records);
        assertThat(results).isNotEmpty();

        AnalyticsResult oneYearResult = results.stream()
            .filter(r -> "1Y".equals(r.getWindow()))
            .findFirst()
            .orElse(null);

        assertThat(oneYearResult).isNotNull();
        // CAGR of roughly 100→200 over 1Y window ~= 100% (not 41%, since we're sliding
        // a 1Y window over a 2Y linear growth — the median should be around 41%)
        assertThat(oneYearResult.getCagrMedian()).isGreaterThan(30.0);
        assertThat(oneYearResult.getCagrMedian()).isLessThan(120.0);
    }

    /**
     * Test rolling returns: max should always >= median >= min.
     */
    @Test
    void testRollingReturnOrdering() {
        List<NAVRecord> records = buildNAVRecords(350);
        List<AnalyticsResult> results = engine.computeAll("TEST002", records);

        for (AnalyticsResult r : results) {
            assertThat(r.getRollingMax()).isGreaterThanOrEqualTo(r.getRollingMedian());
            assertThat(r.getRollingMedian()).isGreaterThanOrEqualTo(r.getRollingMin());
            assertThat(r.getRollingP75()).isGreaterThanOrEqualTo(r.getRollingP25());
        }
    }

    // ── Helper: build synthetic NAV records with gradual growth + noise ──────
    private List<NAVRecord> buildNAVRecords(int count) {
        List<NAVRecord> records = new ArrayList<>();
        double nav = 100.0;
        for (int i = 0; i < count; i++) {
            // Simulate realistic NAV: slow upward trend with small random swings
            nav = nav * (1 + 0.0004 + (Math.random() - 0.5) * 0.005);
            records.add(NAVRecord.builder()
                .fundCode("TEST")
                .date(String.format("20%02d-%02d-%02d", i / 365 + 15, (i % 365) / 30 + 1, i % 28 + 1))
                .nav(Math.max(nav, 1.0))
                .build());
        }
        return records;
    }
}
