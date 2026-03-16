package com.mutualfund.analytics.storage;

import com.mutualfund.analytics.model.AnalyticsResult;
import com.mutualfund.analytics.model.Fund;
import com.mutualfund.analytics.model.NAVRecord;
import com.mutualfund.analytics.model.SyncState;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface — defines all database operations.
 */
public interface StorageService {

    // Fund operations
    void upsertFund(Fund fund);
    List<Fund> getAllFunds();
    List<Fund> getAllFundsFiltered(String category, String amc);
    Optional<Fund> getFundByCode(String code);

    // NAV operations
    void upsertNAVBatch(List<NAVRecord> records);
    List<NAVRecord> getNAVHistory(String fundCode);
    Optional<String> getLatestNAVDate(String fundCode);

    /**
     * Returns the latest NAV record (date + value) for a fund.
     * Used to populate current_nav and last_updated in ranking responses.
     */
    Optional<NAVRecord> getLatestNAV(String fundCode);
    int getNavCount(String fundCode);

    // Analytics operations
    void upsertAnalytics(AnalyticsResult result);
    Optional<AnalyticsResult> getAnalytics(String fundCode, String window);
    List<AnalyticsResult> getAllAnalyticsForFund(String fundCode);

    /**
     * Returns analytics for all funds for a given window (used for ranking).
     */
    List<AnalyticsResult> getAllAnalyticsForWindow(String window);

    // Sync state operations
    void upsertSyncState(SyncState state);
    Optional<SyncState> getSyncState(String fundCode);
    List<SyncState> getAllSyncStates();
}
