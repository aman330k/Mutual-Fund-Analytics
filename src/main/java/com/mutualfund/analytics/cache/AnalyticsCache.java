package com.mutualfund.analytics.cache;

import com.mutualfund.analytics.model.AnalyticsResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for analytics results.
 */
@Slf4j
@Component
public class AnalyticsCache {

    @Value("${cache.ttl-minutes:60}")
    private int ttlMinutes;

    private final Map<String, CacheEntry> store = new ConcurrentHashMap<>();

    /**
     * Returns cached analytics for a fund+window, or empty if absent/expired.
     */
    public List<AnalyticsResult> get(String fundCode) {
        String key = fundCode;
        CacheEntry entry = store.get(key);
        if (entry == null) return null;

        if (isExpired(entry)) {
            store.remove(key);
            log.debug("Cache MISS (expired): {}", key);
            return null;
        }

        log.debug("Cache HIT: {}", key);
        return entry.results;
    }

    /**
     * Stores analytics results for a fund in the cache.
     */
    public void put(String fundCode, List<AnalyticsResult> results) {
        store.put(fundCode, new CacheEntry(results, Instant.now()));
        log.debug("Cache PUT: {} ({} windows)", fundCode, results.size());
    }

    public void invalidate(String fundCode) {
        store.remove(fundCode);
        log.debug("Cache INVALIDATED: {}", fundCode);
    }

    public void clear() {
        store.clear();
        log.info("Cache cleared");
    }

    public int size() {
        return store.size();
    }

    private boolean isExpired(CacheEntry entry) {
        return Instant.now().isAfter(entry.storedAt.plusSeconds((long) ttlMinutes * 60));
    }

    private record CacheEntry(List<AnalyticsResult> results, Instant storedAt) {}
}
