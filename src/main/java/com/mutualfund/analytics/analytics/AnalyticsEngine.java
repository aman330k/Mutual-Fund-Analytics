package com.mutualfund.analytics.analytics;

import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.NAVRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analytics Engine: computes advanced fund metrics from raw NAV history.
 * Supports windows: 1Y, 3Y, 5Y, 10Y
 *
 * Metrics computed:
 * ─────────────────────────────────────────────
 * Rolling Returns:
 *   - Slides a window of N years across the full history
 *   - Computes CAGR for each window position
 *   - Reports: min, max, median, p25, p75
 *
 * Max Drawdown:
 *   - Largest peak-to-trough NAV decline (as %)
 *   - Measures worst possible loss an investor could experience
 *
 * CAGR Distribution:
 *   - Same set of rolling CAGRs, reported as a distribution
 *
 * Data Availability :
 *   - start_date, end_date, total_days, nav_data_points stored per result
 *   - Eliminates an extra DB round-trip in analytics responses
 */
@Slf4j
@Component
public class AnalyticsEngine {

    private static final String[] WINDOWS      = {"1Y", "3Y", "5Y", "10Y"};
    private static final int[]    WINDOW_YEARS  = {1,    3,    5,    10};

    // Approximate trading days per year (Indian markets: ~252 days)
    private static final int TRADING_DAYS_PER_YEAR = 252;
    private static final int MIN_DATA_POINTS        = 30;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Computes all analytics windows (1Y,3Y,5Y,10Y) for a fund's NAV history.
     * Returns only windows where enough data exists.
     *
     * @param fundCode   fund scheme code
     * @param navRecords NAV history ordered oldest-first
     * @return list of computed analytics (one per window)
     */
    public List<AnalyticsResult> computeAll(String fundCode, List<NAVRecord> navRecords) {
        if (navRecords == null || navRecords.size() < MIN_DATA_POINTS) {
            log.warn("Insufficient NAV data for {} ({} records)",
                fundCode, navRecords == null ? 0 : navRecords.size());
            return List.of();
        }

        double[] navValues = navRecords.stream()
            .mapToDouble(NAVRecord::getNav)
            .toArray();

        // Compute data_availability once for this fund — same for all windows
        DataAvailability avail = computeDataAvailability(navRecords);

        List<AnalyticsResult> results = new ArrayList<>();

        for (int i = 0; i < WINDOWS.length; i++) {
            int windowDays = WINDOW_YEARS[i] * TRADING_DAYS_PER_YEAR;
            if (navValues.length < windowDays + MIN_DATA_POINTS) {
                log.info("Skipping {} window for {} — not enough history ({} days available, {} needed)",
                    WINDOWS[i], fundCode, navValues.length, windowDays);
                continue;
            }

            AnalyticsResult result = computeWindow(
                fundCode, navValues, WINDOWS[i], WINDOW_YEARS[i], windowDays, avail);
            if (result != null) {
                results.add(result);
            }
        }

        log.info("Computed {} analytics windows for {}", results.size(), fundCode);
        return results;
    }

    /**
     * Computes analytics for a single time window.
     *
     * Rolling Return Algorithm:
     * 1. For each position i from 0 to (len - windowDays):
     *    - startNav = navValues[i]
     *    - endNav   = navValues[i + windowDays - 1]
     *    - CAGR = (endNav / startNav)^(1/years) - 1
     * 2. Collect all CAGRs, compute percentiles
     */
    private AnalyticsResult computeWindow(String fundCode, double[] navs,
                                          String window, int years, int windowDays,
                                          DataAvailability avail) {
        List<Double> rollingCAGRs = new ArrayList<>();

        // Slide the window across the full history
        for (int i = 0; i <= navs.length - windowDays; i++) {
            double startNav = navs[i];
            double endNav   = navs[i + windowDays - 1];

            if (startNav <= 0) continue;

            // CAGR formula: (endNAV/startNAV)^(1/years) - 1
            double cagr = Math.pow(endNav / startNav, 1.0 / years) - 1.0;
            rollingCAGRs.add(cagr * 100.0);  // convert to percentage
        }

        if (rollingCAGRs.isEmpty()) return null;

        Collections.sort(rollingCAGRs);
        double maxDrawdown = computeMaxDrawdown(navs);

        return AnalyticsResult.builder()
            .fundCode(fundCode)
            .window(window)
            // data_availability fields
            .startDate(avail.startDate)
            .endDate(avail.endDate)
            .totalDays(avail.totalDays)
            .navDataPoints(avail.navDataPoints)
            // rolling returns
            .rollingMin(rollingCAGRs.get(0))
            .rollingMax(rollingCAGRs.get(rollingCAGRs.size() - 1))
            .rollingMedian(percentile(rollingCAGRs, 50))
            .rollingP25(percentile(rollingCAGRs, 25))
            .rollingP75(percentile(rollingCAGRs, 75))
            .maxDrawdown(maxDrawdown)
            // CAGR distribution (same underlying data as rolling returns)
            .cagrMin(rollingCAGRs.get(0))
            .cagrMax(rollingCAGRs.get(rollingCAGRs.size() - 1))
            .cagrMedian(percentile(rollingCAGRs, 50))
            .rollingPeriodsAnalyzed(rollingCAGRs.size())
            .computedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Computes data_availability metadata from the ordered NAV record list.
     *
     * - startDate / endDate: first and last dates in the series
     * - totalDays: calendar days between start and end (inclusive)
     * - navDataPoints: count of records with actual data (trading days only)
     */
    private DataAvailability computeDataAvailability(List<NAVRecord> records) {
        String startDate     = records.get(0).getDate();
        String endDate       = records.get(records.size() - 1).getDate();
        int    navDataPoints = records.size();

        int totalDays;
        try {
            LocalDate start = LocalDate.parse(startDate, DATE_FMT);
            LocalDate end   = LocalDate.parse(endDate,   DATE_FMT);
            totalDays = (int) ChronoUnit.DAYS.between(start, end) + 1;
        } catch (Exception e) {
            totalDays = navDataPoints; // fallback if date parsing fails
        }

        return new DataAvailability(startDate, endDate, totalDays, navDataPoints);
    }

    /**
     * Max Drawdown = largest peak-to-trough decline across the full NAV history.
     *
     * Algorithm:
     * - Track running peak NAV
     * - For each day: drawdown = (nav - peak) / peak
     * - Record the minimum (most negative) drawdown
     *
     * Example: peak=100, trough=65 → drawdown = -35%
     */
    public double computeMaxDrawdown(double[] navs) {
        if (navs.length == 0) return 0;

        double peak  = navs[0];
        double maxDD = 0;

        for (double nav : navs) {
            if (nav > peak) peak = nav;
            double drawdown = (nav - peak) / peak * 100.0;
            if (drawdown < maxDD) maxDD = drawdown;
        }

        return maxDD;  // negative value, e.g. -35.2
    }

    /**
     * Interpolated percentile from a sorted list.
     * p=50 → median, p=25 → Q1, p=75 → Q3
     */
    public double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        if (sorted.size() == 1) return sorted.get(0);

        double rank     = (p / 100.0) * (sorted.size() - 1);
        int    lower    = (int) rank;
        int    upper    = Math.min(lower + 1, sorted.size() - 1);
        double fraction = rank - lower;

        return sorted.get(lower) * (1 - fraction) + sorted.get(upper) * fraction;
    }

    /** Value object carrying data_availability fields computed once per fund. */
    private record DataAvailability(
        String startDate,
        String endDate,
        int    totalDays,
        int    navDataPoints
    ) {}
}
