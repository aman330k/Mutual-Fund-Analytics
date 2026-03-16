package com.mutualfund.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Pipeline checkpoint per fund.
 * After each fund syncs successfully, we write this.
 * On restart, we read this to skip recently-completed funds.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncState {

    @JsonProperty("fund_code")
    private String fundCode;

    @JsonProperty("last_synced_date")
    private String lastSyncedDate;

    @JsonProperty("status")
    private String status;  // "pending", "in_progress", "completed", "failed"

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
