package com.mutualfund.analytics.mfapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mutualfund.analytics.model.Fund;
import com.mutualfund.analytics.model.NAVRecord;
import com.mutualfund.analytics.ratelimiter.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP client for the live mfapi.in API.
 * Key behaviours:
 * - Always calls rateLimiter.waitForSlot() BEFORE making any HTTP call
 * - Converts dates from DD-MM-YYYY (Indian format) to YYYY-MM-DD (ISO 8601)
 * - Reverses the response list (API returns newest-first, we store oldest-first)
 * - Skips NAV entries with value "N/A" or 0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MFAPIClient {

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${mfapi.base-url:https://api.mfapi.in/mf}")
    private String baseUrl;

    @Value("${mfapi.timeout-seconds:30}")
    private int timeoutSeconds;

    // NOTE: In production, use the default HttpClient with proper cert validation.
    // This trusts all certificates
    // See secure coding rule 7.4 — do NOT use in real deployments.
    private final HttpClient httpClient = buildHttpClient();
    
    
    public FetchResult fetchNAVHistory(String schemeCode) throws Exception {
        // === RATE LIMITER GATE ===
        // Block here until all three limits (2/sec, 50/min, 300/hr) allow us through
        rateLimiter.waitForSlot();

        String url = baseUrl + "/" + schemeCode;
        log.info("Fetching NAV history: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", "MutualFundAnalytics-Java/1.0")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 429) {
            log.error("Rate limit violation from MFAPI — 429 received for scheme {}", schemeCode);
            throw new RuntimeException("rate_limit_429: scheme " + schemeCode);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("Unexpected status " + response.statusCode()
                + " for scheme " + schemeCode);
        }

        MFAPIResponse apiResponse = objectMapper.readValue(response.body(), MFAPIResponse.class);

        if (apiResponse.getData() == null || apiResponse.getData().isEmpty()) {
            throw new RuntimeException("Empty data for scheme " + schemeCode);
        }

        // Build Fund metadata from API response
        Fund fund = Fund.builder()
            .code(schemeCode)
            .name(apiResponse.getMeta() != null ? apiResponse.getMeta().getSchemeName() : "")
            .amc(apiResponse.getMeta() != null ? apiResponse.getMeta().getFundHouse() : "")
            .category(apiResponse.getMeta() != null ? apiResponse.getMeta().getSchemeCategory() : "")
            .build();

        // Parse NAV records, skip invalid entries
        List<NAVRecord> records = new ArrayList<>();
        for (MFAPIResponse.DataPoint dp : apiResponse.getData()) {
            String isoDate = convertDate(dp.getDate());
            if (isoDate == null) {
                log.warn("Skipping malformed date '{}' for scheme {}", dp.getDate(), schemeCode);
                continue;
            }
            try {
                double navVal = Double.parseDouble(dp.getNav().trim());
                if (navVal <= 0) continue;  // skip N/A entries
                records.add(NAVRecord.builder()
                    .fundCode(schemeCode)
                    .date(isoDate)
                    .nav(navVal)
                    .build());
            } catch (NumberFormatException e) {
                // Skip non-numeric NAV (e.g., "N/A")
            }
        }

        // API returns newest-first; reverse to oldest-first (chronological)
        Collections.reverse(records);

        log.info("Fetched {} NAV records for scheme {}", records.size(), schemeCode);
        return new FetchResult(records, fund);
    }

    /**
     * Converts "DD-MM-YYYY" to "YYYY-MM-DD".
     * mfapi.in uses Indian date format; we normalize to ISO 8601.
     */
    private String convertDate(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split("-");
        if (parts.length != 3) return null;
        return parts[2] + "-" + parts[1] + "-" + parts[0];
    }

    /**
     * Builds an HttpClient.
     * In production: use HttpClient.newBuilder().connectTimeout(...).build()
     */
    private HttpClient buildHttpClient() {
        try {
            // Trust-all X509TrustManager — not for production
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());

            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .sslContext(sslContext)
                .build();
        } catch (Exception e) {
            log.warn("Could not build trust-all HttpClient ({}), falling back to default", e.getMessage());
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        }
    }

    //  Inner classes for JSON parsing 

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MFAPIResponse {
        private Meta meta;
        private List<DataPoint> data;
        private String status;

        @lombok.Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Meta {
            @JsonProperty("fund_house")    private String fundHouse;
            @JsonProperty("scheme_type")   private String schemeType;
            @JsonProperty("scheme_category") private String schemeCategory;
            @JsonProperty("scheme_name")   private String schemeName;
            @JsonProperty("scheme_code")   private int schemeCode;
        }

        @lombok.Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class DataPoint {
            private String date;  // "DD-MM-YYYY"
            private String nav;   // "104.2551" (string in API response)
        }
    }

    /**
     * Result of a fetchNAVHistory call.
     * Contains the list of NAV records and fund metadata.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class FetchResult {
        private List<NAVRecord> navRecords;
        private Fund fund;
    }
}
