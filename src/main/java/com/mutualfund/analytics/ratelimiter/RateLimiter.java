package com.mutualfund.analytics.ratelimiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Composite 3-dimensional sliding window rate limiter.
 * Enforces mfapi.in limits simultaneously:
 *   - 2 requests per second
 *   - 50 requests per minute
 *   - 300 requests per hour
 *
 * A request is allowed ONLY if ALL three windows allow it.
 *
 * Algorithm: Sliding Window Counter
 * - Tracks exact timestamps of every request
 * - No boundary bursts (unlike Token Bucket)
 * - Exact quota enforcement
 *
 * Thread Safety: Single ReentrantLock protects all three windows atomically.
 *
 * Persistence: Hour + minute window saved to JSON file on each allowed request.
 * Survives server restarts — hourly quota is maintained across restarts.
 */
@Slf4j
@Component
public class RateLimiter {

    @Value("${ratelimiter.per-second:2}")
    private int perSecond;

    @Value("${ratelimiter.per-minute:50}")
    private int perMinute;

    @Value("${ratelimiter.per-hour:300}")
    private int perHour;

    @Value("${ratelimiter.state-file:data/ratelimiter_state.json}")
    private String stateFile;

    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Each list holds timestamps (nanoseconds) of requests within that window
    private final List<Long> secondTimestamps = new ArrayList<>();
    private final List<Long> minuteTimestamps = new ArrayList<>();
    private final List<Long> hourTimestamps   = new ArrayList<>();

    // Observability counters
    private long totalAllowed = 0;
    private long totalBlocked = 0;

    @PostConstruct
    public void init() {
        loadState();
        log.info("Rate limiter initialised: {}/sec, {}/min, {}/hour", perSecond, perMinute, perHour);
    }
    
    public void waitForSlot() throws InterruptedException {
        while (true) {
            lock.lock();
            try {
                long nowNano = System.nanoTime();
                prune(nowNano);

                boolean secOK  = secondTimestamps.size() < perSecond;
                boolean minOK  = minuteTimestamps.size() < perMinute;
                boolean hourOK = hourTimestamps.size()   < perHour;

                if (secOK && minOK && hourOK) {
                    // Record this request in ALL three windows atomically
                    secondTimestamps.add(nowNano);
                    minuteTimestamps.add(nowNano);
                    hourTimestamps.add(nowNano);
                    totalAllowed++;

                    // Save state in background thread (non-blocking)
                    new Thread(this::saveState).start();
                    return;
                }

                long waitNanos = computeWaitNanos(nowNano, secOK, minOK, hourOK);
                totalBlocked++;

                log.debug("Rate limit reached — waiting {}ms | sec={}/{} min={}/{} hour={}/{}",
                    waitNanos / 1_000_000,
                    secondTimestamps.size(), perSecond,
                    minuteTimestamps.size(), perMinute,
                    hourTimestamps.size(), perHour);

                lock.unlock();
                try {
                    Thread.sleep(waitNanos / 1_000_000, (int)(waitNanos % 1_000_000));
                } finally {
                    lock.lock();
                }

            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
    
    private void prune(long nowNano) {
        long oneSecond = 1_000_000_000L;
        long oneMinute = 60_000_000_000L;
        long oneHour   = 3_600_000_000_000L;

        removeOlderThan(secondTimestamps, nowNano - oneSecond);
        removeOlderThan(minuteTimestamps, nowNano - oneMinute);
        removeOlderThan(hourTimestamps,   nowNano - oneHour);
    }

    private void removeOlderThan(List<Long> timestamps, long cutoff) {
        Iterator<Long> it = timestamps.iterator();
        while (it.hasNext()) {
            if (it.next() < cutoff) it.remove();
            else break; // timestamps are ordered, stop early
        }
    }

   
    private long computeWaitNanos(long nowNano, boolean secOK, boolean minOK, boolean hourOK) {
        long maxWait = 0;
        long oneSecond = 1_000_000_000L;
        long oneMinute = 60_000_000_000L;
        long oneHour   = 3_600_000_000_000L;

        if (!secOK && !secondTimestamps.isEmpty()) {
            long expiry = secondTimestamps.get(0) + oneSecond - nowNano;
            maxWait = Math.max(maxWait, expiry);
        }
        if (!minOK && !minuteTimestamps.isEmpty()) {
            long expiry = minuteTimestamps.get(0) + oneMinute - nowNano;
            maxWait = Math.max(maxWait, expiry);
        }
        if (!hourOK && !hourTimestamps.isEmpty()) {
            long expiry = hourTimestamps.get(0) + oneHour - nowNano;
            maxWait = Math.max(maxWait, expiry);
        }

        // Add 5ms buffer to avoid off-by-one timing issues
        return Math.max(maxWait, 10_000_000L) + 5_000_000L;
    }

    /**
     * Returns current usage stats for the /sync/status endpoint.
     */
    public int[] getStats() {
        lock.lock();
        try {
            prune(System.nanoTime());
            return new int[]{
                secondTimestamps.size(),
                minuteTimestamps.size(),
                hourTimestamps.size()
            };
        } finally {
            lock.unlock();
        }
    }

    public long getTotalAllowed() { return totalAllowed; }
    public long getTotalBlocked() { return totalBlocked; }
    public int getPerSecond() { return perSecond; }
    public int getPerMinute() { return perMinute; }
    public int getPerHour()   { return perHour; }

    //  State Persistence 

   
    private void saveState() {
        List<Long> hourCopy, minuteCopy;
        lock.lock();
        try {
            hourCopy   = new ArrayList<>(hourTimestamps);
            minuteCopy = new ArrayList<>(minuteTimestamps);
        } finally {
            lock.unlock();
        }

        try {
            // Capture a consistent reference point for the conversion
            long nowNano   = System.nanoTime();
            long nowWallMs = System.currentTimeMillis();

            List<Long> hourWall   = hourCopy.stream()
                .map(t -> nowWallMs - (nowNano - t) / 1_000_000)
                .toList();
            List<Long> minuteWall = minuteCopy.stream()
                .map(t -> nowWallMs - (nowNano - t) / 1_000_000)
                .toList();

            new File("data").mkdirs();
            PersistedState state = new PersistedState(hourWall, minuteWall, nowWallMs);
            objectMapper.writeValue(new File(stateFile), state);
        } catch (IOException e) {
            log.error("Failed to save rate limiter state: {}", e.getMessage());
        }
    }

   
    private void loadState() {
        try {
            Path path = Path.of(stateFile);
            if (!Files.exists(path)) {
                log.warn("No rate limiter state file found — starting fresh");
                return;
            }

            PersistedState state = objectMapper.readValue(path.toFile(), PersistedState.class);

            // Reference point for converting wall clock → current JVM's nanoTime
            long nowNano   = System.nanoTime();
            long nowWallMs = System.currentTimeMillis();

            long oneMinuteMs = 60_000L;
            long oneHourMs   = 3_600_000L;

            long cutoffHourMs   = nowWallMs - oneHourMs;
            long cutoffMinuteMs = nowWallMs - oneMinuteMs;

            if (state.getHourTimestamps() != null) {
                state.getHourTimestamps().stream()
                    .filter(wallMs -> wallMs > cutoffHourMs)    // still within 1-hour window
                    .map(wallMs -> nowNano - (nowWallMs - wallMs) * 1_000_000) // convert to nanoTime
                    .forEach(hourTimestamps::add);
            }
            if (state.getMinuteTimestamps() != null) {
                state.getMinuteTimestamps().stream()
                    .filter(wallMs -> wallMs > cutoffMinuteMs)  // still within 1-minute window
                    .map(wallMs -> nowNano - (nowWallMs - wallMs) * 1_000_000) // convert to nanoTime
                    .forEach(minuteTimestamps::add);
            }

            log.info("Restored rate limiter state — hour: {}/{}, minute: {}/{}",
                hourTimestamps.size(), perHour, minuteTimestamps.size(), perMinute);

        } catch (IOException e) {
            log.warn("Could not load rate limiter state (starting fresh): {}", e.getMessage());
        }
    }

    /**
     * DTO for JSON persistence.
     * hourTimestamps / minuteTimestamps are stored as epoch milliseconds (wall clock),
     * NOT as System.nanoTime() values, so they survive JVM restarts.
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    static class PersistedState {
        private List<Long> hourTimestamps;    // epoch millis (wall clock)
        private List<Long> minuteTimestamps;  // epoch millis (wall clock)
        private long savedAt;                 // epoch millis of last save
    }
}
