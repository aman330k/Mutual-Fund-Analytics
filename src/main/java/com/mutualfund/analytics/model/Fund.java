package com.mutualfund.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fund {

    @JsonProperty("fund_code")
    private String code;

    @JsonProperty("fund_name")
    private String name;

    @JsonProperty("amc")
    private String amc;

    @JsonProperty("category")
    private String category;

    @JsonProperty("inception_date")
    private String inceptionDate;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
