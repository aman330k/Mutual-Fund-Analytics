package com.mutualfund.analytics.pipeline;

import com.mutualfund.analytics.analytics.AnalyticsEngine;
import com.mutualfund.analytics.cache.AnalyticsCache;
import com.mutualfund.analytics.mfapi.MFAPIClient;
import com.mutualfund.analytics.model.*;
import com.mutualfund.analytics.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Data ingestion pipeline: fetches NAV history, stores it, runs analytics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataPipeline {

    private final StorageService storage;
    private final MFAPIClient    mfClient;
    private final AnalyticsEngine analyticsEngine;
    private final AnalyticsCache  cache;

    @Value("${pipeline.workers:2}")
    private int numWorkers;

    @Value("${pipeline.recent-sync-hours:12}")
    private int recentSyncHours;

    // AtomicBoolean: thread-safe flag without a full lock
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Progress tracking (exposed to /sync/status endpoint)
    private final AtomicInteger completed = new AtomicInteger(0);
    private final AtomicInteger failed    = new AtomicInteger(0);
    private final AtomicInteger total     = new AtomicInteger(0);

    private volatile LocalDateTime lastSyncStartedAt = null;

    /**
     * Triggers a background sync.
     * Returns immediately; pipeline runs in a separate thread pool.
     *
     * @Async = Spring's way to run a method in a background thread.
     */
    @Async
    public void triggerSync() {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Sync already in progress — ignoring trigger");
            return;
        }

        try {
            run();
        } finally {
            isRunning.set(false);
            log.info("Pipeline completed — success: {}, failed: {}/{}", completed.get(), failed.get(), total.get());
        }
    }
    
    private void run() {
        log.info("=== Pipeline starting ===");
        lastSyncStartedAt = LocalDateTime.now();
        completed.set(0);
        failed.set(0);

        // Step 1: Register all tracked funds in DB (idempotent)
        ensureFundsRegistered();

        List<Fund> funds = FundRegistry.ALL_FUNDS;
        total.set(funds.size());

        // Step 2: Process funds in parallel using a thread pool
        ExecutorService pool = Executors.newFixedThreadPool(numWorkers);

        List<Future<?>> futures = new ArrayList<>();
        for (Fund fund : funds) {
            futures.add(pool.submit(() -> processFund(fund)));
        }

        // Wait for all funds to complete
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("Worker task failed: {}", e.getMessage());
            }
        }

        pool.shutdown();
        log.info("=== Pipeline finished: {}/{} funds synced successfully ===",
            completed.get(), total.get());
    }

    /**
     * Processes a single fund: fetch → store → analytics.
     * Sets sync_state checkpoints at each stage for crash recovery.
     */
    private void processFund(Fund fund) {
        String code = fund.getCode();

        // Skip recently synced funds (crash recovery + incremental update)
        if (wasRecentlySynced(code)) {
            log.info("Skipping {} — synced within last {}h", code, recentSyncHours);
            completed.incrementAndGet();
            return;
        }

        log.info("Processing fund: {} ({})", fund.getName(), code);

        // Checkpoint: mark in_progress BEFORE fetching
        saveSyncState(code, "in_progress", null);

        try {
            // Step 1: Fetch NAV history from live API (rate-limited)
            MFAPIClient.FetchResult result = mfClient.fetchNAVHistory(code);
            List<NAVRecord> records = result.getNavRecords();

            if (records.isEmpty()) {
                throw new RuntimeException("No NAV records returned");
            }

            // Step 2: Persist NAV records (upsert = safe to re-run)
            storage.upsertNAVBatch(records);
            log.info("Stored {} NAV records for {}", records.size(), code);

            // Step 3: Compute analytics from stored history
            List<NAVRecord> fullHistory = storage.getNAVHistory(code);
            List<AnalyticsResult> analytics = analyticsEngine.computeAll(code, fullHistory);

            for (AnalyticsResult ar : analytics) {
                storage.upsertAnalytics(ar);
            }

            // Step 4: Invalidate cache so next API call fetches fresh analytics
            cache.invalidate(code);

            // Checkpoint: mark completed
            String latestDate = records.get(records.size() - 1).getDate();
            saveSyncState(code, "completed", latestDate);
            completed.incrementAndGet();

            log.info("Fund {} synced successfully — {} analytics windows computed", code, analytics.size());

        } catch (Exception e) {
            log.error("Failed to process fund {}: {}", code, e.getMessage());
            saveSyncState(code, "failed", null);
            failed.incrementAndGet();
        }
    }

    /**
     * Ensures all tracked funds exist in the funds table.
     * Idempotent — safe to call on every sync.
     */
    private void ensureFundsRegistered() {
        for (Fund fund : FundRegistry.ALL_FUNDS) {
            storage.upsertFund(fund);
        }
        log.info("Registered {} funds in database", FundRegistry.ALL_FUNDS.size());
    }

    /**
     * Checks if a fund was successfully synced within the recentSyncHours window.
     * Prevents redundant API calls for frequently triggered syncs.
     */
    private boolean wasRecentlySynced(String fundCode) {
        Optional<SyncState> state = storage.getSyncState(fundCode);
        if (state.isEmpty()) return false;
        if (!"completed".equals(state.get().getStatus())) return false;

        LocalDateTime updatedAt = state.get().getUpdatedAt();
        if (updatedAt == null) return false;

        return updatedAt.isAfter(LocalDateTime.now().minusHours(recentSyncHours));
    }

    /**
     * Writes or updates the sync checkpoint for a fund.
     */
    private void saveSyncState(String fundCode, String status, String lastSyncedDate) {
        storage.upsertSyncState(SyncState.builder()
            .fundCode(fundCode)
            .status(status)
            .lastSyncedDate(lastSyncedDate)
            .updatedAt(LocalDateTime.now())
            .build());
    }

    // Status getters for the /sync/status API

    public boolean isRunning()             { return isRunning.get(); }
    public int     getCompleted()          { return completed.get(); }
    public int     getFailed()             { return failed.get(); }
    public int     getTotal()              { return total.get(); }
    public LocalDateTime getLastStartedAt(){ return lastSyncStartedAt; }
}
