package com.mutualfund.analytics.api;

import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.Fund;
import com.mutualfund.analytics.model.NAVRecord;
import com.mutualfund.analytics.service.FundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;


@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FundController {

    private static final Set<String> VALID_WINDOWS  = Set.of("1Y", "3Y", "5Y", "10Y");
    private static final Set<String> VALID_SORT_BY  = Set.of("median_return", "max_drawdown");

    private final FundService fundService;

    //Fund endpoints
    @GetMapping("/funds")
    public ResponseEntity<Map<String, Object>> getAllFunds(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String amc) {

        List<Fund> funds = (category != null || amc != null)
            ? fundService.getAllFundsFiltered(category, amc)
            : fundService.getAllFunds();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("funds", funds);
        body.put("count", funds.size());
        if (category != null) body.put("filter_category", category);
        if (amc      != null) body.put("filter_amc",      amc);

        return ResponseEntity.ok(body);
    }


    @GetMapping("/funds/rank")
    public ResponseEntity<?> rankFunds(
            @RequestParam String category,
            @RequestParam String window,
            @RequestParam(defaultValue = "median_return") String sort_by,
            @RequestParam(defaultValue = "5") int limit) {

        String win = window.toUpperCase();
        if (!VALID_WINDOWS.contains(win)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "window must be one of: 1Y, 3Y, 5Y, 10Y"));
        }
        if (!VALID_SORT_BY.contains(sort_by)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "sort_by must be one of: median_return, max_drawdown"));
        }
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "limit must be between 1 and 100"));
        }

        FundService.RankingResponse ranking = fundService.rankFunds(category, win, sort_by, limit);
        String windowLower = win.toLowerCase();

        List<Map<String, Object>> fundsJson = ranking.funds().stream()
            .map(rf -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank",                               rf.rank());
                m.put("fund_code",                          rf.fundCode());
                m.put("fund_name",                          rf.fundName());
                m.put("amc",                                rf.amc());
                m.put("median_return_" + windowLower,       round2(rf.medianReturn()));
                m.put("max_drawdown_"  + windowLower,       round2(rf.maxDrawdown()));
                m.put("current_nav",                        rf.currentNav());
                m.put("last_updated",                       rf.lastUpdated());
                return m;
            })
            .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category",    ranking.category());
        body.put("window",      ranking.window());
        body.put("sorted_by",   ranking.sortedBy());
        body.put("total_funds", ranking.totalFunds());
        body.put("showing",     ranking.showing());
        body.put("funds",       fundsJson);

        return ResponseEntity.ok(body);
    }

    @GetMapping("/funds/{code}")
    public ResponseEntity<?> getFund(@PathVariable String code) {
        Optional<Fund> fund = fundService.getFundByCode(code);
        if (fund.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Fund not found: " + code));
        }

        Optional<NAVRecord> latestNAV = fundService.getLatestNAV(code);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fund_code",   fund.get().getCode());
        body.put("fund_name",   fund.get().getName());
        body.put("amc",         fund.get().getAmc());
        body.put("category",    fund.get().getCategory());
        body.put("current_nav", latestNAV.map(NAVRecord::getNav).orElse(null));
        body.put("last_updated", latestNAV.map(NAVRecord::getDate).orElse(null));

        return ResponseEntity.ok(body);
    }


    @GetMapping("/funds/{code}/nav")
    public ResponseEntity<?> getNAVHistory(@PathVariable String code) {
        Optional<Fund> fund = fundService.getFundByCode(code);
        if (fund.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Fund not found: " + code));
        }

        List<NAVRecord> records = fundService.getNAVHistory(code);
        return ResponseEntity.ok(Map.of(
            "fund_code", code,
            "count", records.size(),
            "records", records
        ));
    }

    //Analytics endpoints
    @GetMapping("/funds/{code}/analytics")
    public ResponseEntity<?> getAnalytics(
            @PathVariable String code,
            @RequestParam(required = false) String window) {

        Optional<Fund> fund = fundService.getFundByCode(code);
        if (fund.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Fund not found: " + code));
        }

        if (window != null) {
            //  Spec format for a single window 
            String win = window.toUpperCase();
            if (!VALID_WINDOWS.contains(win)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "window must be one of: 1Y, 3Y, 5Y, 10Y"));
            }

            Optional<AnalyticsResult> result = fundService.getAnalyticsForWindow(code, win);
            if (result.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "fund_code", code,
                    "window",    win,
                    "message",   "No analytics for this window yet. Trigger a sync first."
                ));
            }

            return ResponseEntity.ok(buildSpecAnalyticsResponse(fund.get(), result.get()));
        }

        List<AnalyticsResult> results = fundService.getAnalytics(code);
        if (results.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "fund_code", code,
                "message",   "No analytics available yet. Trigger a sync first.",
                "analytics", List.of()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "fund_code",  code,
            "fund_name",  fund.get().getName(),
            "analytics",  results
        ));
    }

    @GetMapping("/funds/{code}/analytics/{window}")
    public ResponseEntity<?> getAnalyticsForWindow(
            @PathVariable String code,
            @PathVariable String window) {

        Optional<Fund> fund = fundService.getFundByCode(code);
        if (fund.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Fund not found: " + code));
        }

        String win = window.toUpperCase();
        if (!VALID_WINDOWS.contains(win)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "window must be one of: 1Y, 3Y, 5Y, 10Y"));
        }

        Optional<AnalyticsResult> result = fundService.getAnalyticsForWindow(code, win);
        if (result.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "fund_code", code,
                "window",    win,
                "message",   "No analytics for this window yet."
            ));
        }

        return ResponseEntity.ok(buildSpecAnalyticsResponse(fund.get(), result.get()));
    }


    private Map<String, Object> buildSpecAnalyticsResponse(Fund fund, AnalyticsResult ar) {
        Map<String, Object> dataAvailability = new LinkedHashMap<>();
        dataAvailability.put("start_date",      ar.getStartDate());
        dataAvailability.put("end_date",         ar.getEndDate());
        dataAvailability.put("total_days",       ar.getTotalDays());
        dataAvailability.put("nav_data_points",  ar.getNavDataPoints());

        Map<String, Object> rollingReturns = new LinkedHashMap<>();
        rollingReturns.put("min",    round2(ar.getRollingMin()));
        rollingReturns.put("max",    round2(ar.getRollingMax()));
        rollingReturns.put("median", round2(ar.getRollingMedian()));
        rollingReturns.put("p25",    round2(ar.getRollingP25()));
        rollingReturns.put("p75",    round2(ar.getRollingP75()));

        Map<String, Object> cagr = new LinkedHashMap<>();
        cagr.put("min",    round2(ar.getCagrMin()));
        cagr.put("max",    round2(ar.getCagrMax()));
        cagr.put("median", round2(ar.getCagrMedian()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fund_code",               ar.getFundCode());
        body.put("fund_name",               fund.getName());
        body.put("category",                fund.getCategory());
        body.put("amc",                     fund.getAmc());
        body.put("window",                  ar.getWindow());
        body.put("data_availability",       dataAvailability);
        body.put("rolling_periods_analyzed", ar.getRollingPeriodsAnalyzed());
        body.put("rolling_returns",         rollingReturns);
        body.put("max_drawdown",            round2(ar.getMaxDrawdown()));
        body.put("cagr",                    cagr);
        body.put("computed_at",             ar.getComputedAt());
        return body;
    }

    @GetMapping("/analytics/ranking")
    public ResponseEntity<?> getRanking(
            @RequestParam(defaultValue = "3Y") String window) {

        List<AnalyticsResult> ranked = fundService.rankFundsByWindow(window.toUpperCase());
        return ResponseEntity.ok(Map.of(
            "window",       window.toUpperCase(),
            "count",        ranked.size(),
            "ranked_funds", ranked
        ));
    }


    @PostMapping("/sync/trigger")
    public ResponseEntity<Map<String, String>> triggerSync() {
        if (fundService.getPipelineStatus().running()) {
            return ResponseEntity.ok(Map.of(
                "status",  "already_running",
                "message", "Sync is already in progress"
            ));
        }

        fundService.triggerSync();
        return ResponseEntity.ok(Map.of(
            "status",  "started",
            "message", "Sync pipeline triggered successfully"
        ));
    }

    @GetMapping("/sync/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        FundService.SyncPipelineStatus pipelineStatus = fundService.getPipelineStatus();

        return ResponseEntity.ok(Map.of(
            "pipeline", Map.of(
                "running",         pipelineStatus.running(),
                "completed",       pipelineStatus.completed(),
                "failed",          pipelineStatus.failed(),
                "total",           pipelineStatus.total(),
                "last_started_at", pipelineStatus.lastStartedAt() != null
                    ? pipelineStatus.lastStartedAt().toString() : "never"
            ),
            "funds", fundService.getSyncStatus()
        ));
    }

    // ─ Metrics endpoint ─

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Runtime rt = Runtime.getRuntime();

        FundService.SyncPipelineStatus pipeline = fundService.getPipelineStatus();
        List<Fund> funds = fundService.getAllFunds();

        return ResponseEntity.ok(Map.of(
            "jvm", Map.of(
                "used_memory_mb",  (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
                "total_memory_mb", rt.totalMemory() / (1024 * 1024),
                "free_memory_mb",  rt.freeMemory()  / (1024 * 1024),
                "processors",      rt.availableProcessors()
            ),
            "pipeline", Map.of(
                "running",   pipeline.running(),
                "completed", pipeline.completed(),
                "failed",    pipeline.failed(),
                "total",     pipeline.total()
            ),
            "storage", Map.of(
                "funds_tracked", funds.size()
            ),
            "version", "1.1.0"
        ));
    }
    

    /** Round a double to 2 decimal places for cleaner JSON output. */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
