package com.mutualfund.analytics.service;

import com.mutualfund.analytics.cache.AnalyticsCache;
import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.Fund;
import com.mutualfund.analytics.model.NAVRecord;
import com.mutualfund.analytics.model.SyncState;
import com.mutualfund.analytics.pipeline.DataPipeline;
import com.mutualfund.analytics.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core business logic layer.
 * Controllers call this; this calls storage/cache/pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundService {

    private final StorageService storage;
    private final AnalyticsCache cache;
    private final DataPipeline   pipeline;

    //  Fund queries
    public List<Fund> getAllFunds() {
        return storage.getAllFunds();
    }

    public List<Fund> getAllFundsFiltered(String category, String amc) {
        return storage.getAllFundsFiltered(category, amc);
    }

    public Optional<Fund> getFundByCode(String code) {
        return storage.getFundByCode(code);
    }

    //  NAV queries
    public List<NAVRecord> getNAVHistory(String fundCode) {
        return storage.getNAVHistory(fundCode);
    }

    public Optional<NAVRecord> getLatestNAV(String fundCode) {
        return storage.getLatestNAV(fundCode);
    }

    //  Analytics queries
    public List<AnalyticsResult> getAnalytics(String fundCode) {
        // Cache check
        List<AnalyticsResult> cached = cache.get(fundCode);
        if (cached != null) {
            return cached;
        }

        // DB fetch
        List<AnalyticsResult> results = storage.getAllAnalyticsForFund(fundCode);
        if (!results.isEmpty()) {
            cache.put(fundCode, results);
        }
        return results;
    }

    public Optional<AnalyticsResult> getAnalyticsForWindow(String fundCode, String window) {
        return storage.getAnalytics(fundCode, window);
    }

    //  Ranking
    public List<AnalyticsResult> rankFundsByWindow(String window) {
        List<Fund> funds = storage.getAllFunds();

        return funds.stream()
            .map(f -> storage.getAnalytics(f.getCode(), window))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .sorted(Comparator.comparingDouble(AnalyticsResult::getCagrMedian).reversed())
            .toList();
    }


    public RankingResponse rankFunds(String category, String window, String sortBy, int limit) {
        // Get funds filtered by category
        List<Fund> funds = storage.getAllFundsFiltered(category, null);

        // Build a map of fundCode → Fund for O(1) lookups
        Map<String, Fund> fundMap = funds.stream()
            .collect(Collectors.toMap(Fund::getCode, f -> f));

        Set<String> fundCodes = fundMap.keySet();

        // Fetch analytics for the requested window for all these funds
        //  Use getAllAnalyticsForWindow() and filter to our fund set for efficiency
        List<AnalyticsResult> analyticsForWindow = storage.getAllAnalyticsForWindow(window)
            .stream()
            .filter(ar -> fundCodes.contains(ar.getFundCode()))
            .toList();

        int totalFunds = analyticsForWindow.size();

        // Sort by the requested metric
        Comparator<AnalyticsResult> comparator = "max_drawdown".equalsIgnoreCase(sortBy)
            // Max drawdown is negative; "best" = closest to 0 = highest value (ascending absolute)
            ? Comparator.comparingDouble(AnalyticsResult::getMaxDrawdown).reversed()
            : Comparator.comparingDouble(AnalyticsResult::getCagrMedian).reversed();

        List<AnalyticsResult> sorted = analyticsForWindow.stream()
            .sorted(comparator)
            .toList();

        // Apply limit
        List<AnalyticsResult> paged = sorted.stream().limit(limit).toList();

        // Build ranked fund entries
        List<RankedFund> rankedFunds = new java.util.ArrayList<>();
        for (int i = 0; i < paged.size(); i++) {
            AnalyticsResult ar = paged.get(i);
            Fund fund = fundMap.get(ar.getFundCode());
            if (fund == null) continue;

            Optional<NAVRecord> latestNAV = storage.getLatestNAV(ar.getFundCode());

            rankedFunds.add(new RankedFund(
                i + 1,
                ar.getFundCode(),
                fund.getName(),
                fund.getAmc(),
                ar.getCagrMedian(),
                ar.getMaxDrawdown(),
                latestNAV.map(NAVRecord::getNav).orElse(0.0),
                latestNAV.map(NAVRecord::getDate).orElse(null)
            ));
        }

        String effectiveSortBy = "max_drawdown".equalsIgnoreCase(sortBy)
            ? "max_drawdown" : "median_return";

        return new RankingResponse(category, window, effectiveSortBy, totalFunds,
            rankedFunds.size(), rankedFunds);
    }

    //  Sync control
    public void triggerSync() {
        pipeline.triggerSync();
    }

    public List<SyncState> getSyncStatus() {
        return storage.getAllSyncStates();
    }

    public SyncPipelineStatus getPipelineStatus() {
        return new SyncPipelineStatus(
            pipeline.isRunning(),
            pipeline.getCompleted(),
            pipeline.getFailed(),
            pipeline.getTotal(),
            pipeline.getLastStartedAt()
        );
    }

    // Inner DTOs
    public record SyncPipelineStatus(
        boolean running,
        int completed,
        int failed,
        int total,
        java.time.LocalDateTime lastStartedAt
    ) {}

    /**
     * Top-level ranking response matching the spec format.
     */
    public record RankingResponse(
        String category,
        String window,
        String sortedBy,
        int totalFunds,
        int showing,
        List<RankedFund> funds
    ) {}


    public record RankedFund(
        int    rank,
        String fundCode,
        String fundName,
        String amc,
        double medianReturn,
        double maxDrawdown,
        double currentNav,
        String lastUpdated
    ) {}
}
