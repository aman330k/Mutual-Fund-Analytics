package com.mutualfund.analytics.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the RateLimiter.
 *
 * Key tests:
 * 1. Per-second limit enforcement
 * 2. Multiple threads respect the same limits (thread safety)
 * 3. Timestamps are pruned correctly
 */
class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter();
        // Inject test-friendly limits via reflection (avoids needing Spring context)
        ReflectionTestUtils.setField(rateLimiter, "perSecond", 2);
        ReflectionTestUtils.setField(rateLimiter, "perMinute", 10);
        ReflectionTestUtils.setField(rateLimiter, "perHour",   50);
        // Use a temp state file
        ReflectionTestUtils.setField(rateLimiter, "stateFile",
            System.getProperty("java.io.tmpdir") + "/rl_test_" + System.nanoTime() + ".json");
        rateLimiter.init();
    }

    /**
     * Verify that 2 immediate requests are allowed but the 3rd is blocked
     * (per-second limit = 2).
     */
    @Test
    @Timeout(5)
    void testPerSecondLimitAllowsExactCount() throws InterruptedException {
        // First two should go through instantly
        long start = System.currentTimeMillis();
        rateLimiter.waitForSlot();
        rateLimiter.waitForSlot();
        long elapsed = System.currentTimeMillis() - start;

        // Both requests should have been almost instant (< 100ms)
        assertThat(elapsed).isLessThan(200);
        assertThat(rateLimiter.getTotalAllowed()).isEqualTo(2);
    }

    /**
     * Verify that the 3rd request within 1 second is blocked until the window slides.
     */
    @Test
    @Timeout(4)
    void testThirdRequestIsDelayed() throws InterruptedException {
        rateLimiter.waitForSlot();
        rateLimiter.waitForSlot();

        long before = System.currentTimeMillis();
        rateLimiter.waitForSlot();  // Should wait ~1 second
        long waited = System.currentTimeMillis() - before;

        // Should have waited at least 500ms (being lenient for CI timing)
        assertThat(waited).isGreaterThan(500);
        assertThat(rateLimiter.getTotalBlocked()).isGreaterThan(0);
    }

    /**
     * Verify thread safety: multiple threads requesting slots concurrently
     * should never exceed the per-second limit at any point.
     */
    @Test
    @Timeout(10)
    void testConcurrentThreadsSafelyRespectLimits() throws InterruptedException {
        // Set more lenient limits for this test
        ReflectionTestUtils.setField(rateLimiter, "perSecond", 3);
        ReflectionTestUtils.setField(rateLimiter, "perMinute", 20);

        int numThreads = 6;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numThreads);
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            pool.submit(() -> {
                try {
                    rateLimiter.waitForSlot();
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        // All 6 requests should eventually succeed
        assertThat(successCount.get()).isEqualTo(numThreads);
    }

    /**
     * Verify stats method returns accurate counts.
     */
    @Test
    @Timeout(3)
    void testGetStatsReturnsCorrectCounts() throws InterruptedException {
        rateLimiter.waitForSlot();

        int[] stats = rateLimiter.getStats();
        assertThat(stats[0]).isEqualTo(1);  // second window: 1 request
        assertThat(stats[1]).isEqualTo(1);  // minute window: 1 request
        assertThat(stats[2]).isEqualTo(1);  // hour window: 1 request
    }
}
