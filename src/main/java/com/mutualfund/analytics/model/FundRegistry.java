package com.mutualfund.analytics.model;

import java.util.List;

/**
 * Registry of all 10 tracked mutual funds.
 * Scheme codes from https://api.mfapi.in/mf/search
 */
public class FundRegistry {

    public static final List<Fund> ALL_FUNDS = List.of(
        // ICICI Prudential
        Fund.builder().code("120586").name("ICICI Prudential Midcap Direct Plan Growth")
            .amc("ICICI Prudential Mutual Fund").category("Equity: Mid Cap Direct Growth").build(),
        Fund.builder().code("120587").name("ICICI Prudential Smallcap Direct Plan Growth")
            .amc("ICICI Prudential Mutual Fund").category("Equity: Small Cap Direct Growth").build(),
        // HDFC
        Fund.builder().code("118989").name("HDFC Mid-Cap Opportunities Direct Plan Growth")
            .amc("HDFC Mutual Fund").category("Equity: Mid Cap Direct Growth").build(),
        Fund.builder().code("118990").name("HDFC Small Cap Direct Plan Growth")
            .amc("HDFC Mutual Fund").category("Equity: Small Cap Direct Growth").build(),
        // Axis
        Fund.builder().code("119598").name("Axis Midcap Direct Plan Growth")
            .amc("Axis Mutual Fund").category("Equity: Mid Cap Direct Growth").build(),
        Fund.builder().code("125497").name("Axis Small Cap Direct Plan Growth")
            .amc("Axis Mutual Fund").category("Equity: Small Cap Direct Growth").build(),
        // SBI
        Fund.builder().code("119823").name("SBI Magnum Midcap Direct Plan Growth")
            .amc("SBI Mutual Fund").category("Equity: Mid Cap Direct Growth").build(),
        Fund.builder().code("125354").name("SBI Small Cap Direct Plan Growth")
            .amc("SBI Mutual Fund").category("Equity: Small Cap Direct Growth").build(),
        // Kotak
        Fund.builder().code("120503").name("Kotak Emerging Equity Direct Plan Growth")
            .amc("Kotak Mahindra Mutual Fund").category("Equity: Mid Cap Direct Growth").build(),
        Fund.builder().code("120838").name("Kotak Small Cap Direct Plan Growth")
            .amc("Kotak Mahindra Mutual Fund").category("Equity: Small Cap Direct Growth").build()
    );

    private FundRegistry() {}
}
