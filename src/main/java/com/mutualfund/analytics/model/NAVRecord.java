package com.mutualfund.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NAVRecord {

    @JsonProperty("fund_code")
    private String fundCode;

    @JsonProperty("date")
    private String date;  // Format: "YYYY-MM-DD"

    @JsonProperty("nav")
    private double nav;
}
