package com.mutualfund.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the Mutual Fund Analytics Platform.
 * @EnableAsync allows pipeline to run in background threads
 */
@SpringBootApplication
@EnableAsync
public class MutualFundAnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(MutualFundAnalyticsApplication.class, args);
    }
}
