package com.mutualfund.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Pre-computed analytics for a fund over a time window.
 * Computed once during sync, stored in DB, served fast (<200ms).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsResult {

    @JsonProperty("fund_code")
    private String fundCode;

    @JsonProperty("window")
    private String window;  // "1Y", "3Y", "5Y", "10Y"

    /** Earliest NAV date in the fund's history (YYYY-MM-DD). */
    @JsonProperty("start_date")
    private String startDate;

    /** Latest NAV date in the fund's history (YYYY-MM-DD). */
    @JsonProperty("end_date")
    private String endDate;

    /** Calendar days between startDate and endDate (inclusive). */
    @JsonProperty("total_days")
    private int totalDays;

    /** Number of trading days with NAV data in the history. */
    @JsonProperty("nav_data_points")
    private int navDataPoints;

    //  Rolling returns: computed over every possible N-year window in history 

    @JsonProperty("rolling_min")
    private double rollingMin;

    @JsonProperty("rolling_max")
    private double rollingMax;

    @JsonProperty("rolling_median")
    private double rollingMedian;

    @JsonProperty("rolling_p25")
    private double rollingP25;  // 25th percentile

    @JsonProperty("rolling_p75")
    private double rollingP75;  // 75th percentile

    //  Max Drawdown: worst peak-to-trough decline 

    @JsonProperty("max_drawdown")
    private double maxDrawdown;

    //  CAGR distribution across all rolling periods 

    @JsonProperty("cagr_min")
    private double cagrMin;

    @JsonProperty("cagr_max")
    private double cagrMax;

    @JsonProperty("cagr_median")
    private double cagrMedian;

    @JsonProperty("rolling_periods_analyzed")
    private int rollingPeriodsAnalyzed;

    @JsonProperty("computed_at")
    private LocalDateTime computedAt;
}
