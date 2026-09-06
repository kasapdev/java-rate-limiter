package dev.kasapdev.ratelimiter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SlidingWindowRateLimiterTest {

    public static void main(String[] args) throws Exception {
        testBasicAdmissionUpToLimit();
        testWindowSlidesAndAdmitsAgainAfterExpiry();
        testInvalidConstructorArgsRejected();
        testConcurrencyNeverExceedsMaxRequestsWithinWindow();
        testFreshLimiterHasZeroCount();
        testRejectedAttemptDoesNotChangeCount();

        TestKit.finish();
    }

    private static void testFreshLimiterHasZeroCount() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(5, 10_000);
        TestKit.check("a brand-new limiter with no requests yet reports currentCount() == 0", limiter.currentCount() == 0);
    }

    private static void testRejectedAttemptDoesNotChangeCount() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 10_000);
        TestKit.check("1st request admitted", limiter.tryAcquire());
        TestKit.check("2nd request admitted", limiter.tryAcquire());
        int countBeforeRejection = limiter.currentCount();
        TestKit.check("3rd request is rejected (window full)", !limiter.tryAcquire());
        TestKit.check(
                "a rejected attempt does not change currentCount()",
                limiter.currentCount() == countBeforeRejection);
    }

    private static void testBasicAdmissionUpToLimit() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, 10_000);
        TestKit.check("1st request admitted", limiter.tryAcquire());
        TestKit.check("2nd request admitted", limiter.tryAcquire());
        TestKit.check("3rd request admitted", limiter.tryAcquire());
        TestKit.check("4th request within window rejected", !limiter.tryAcquire());
        TestKit.check("current count reflects admitted requests", limiter.currentCount() == 3);
    }

    private static void testWindowSlidesAndAdmitsAgainAfterExpiry() throws InterruptedException {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 100);
        TestKit.check("1st request admitted", limiter.tryAcquire());
        TestKit.check("2nd request admitted", limiter.tryAcquire());
        TestKit.check("3rd request rejected while window full", !limiter.tryAcquire());
        Thread.sleep(150);
        TestKit.check("request admitted again after window expires", limiter.tryAcquire());
        TestKit.check("count reflects only the fresh request after expiry", limiter.currentCount() == 1);
    }

    private static void testInvalidConstructorArgsRejected() {
        TestKit.check("zero maxRequests rejected", throwsIllegalArgument(() -> new SlidingWindowRateLimiter(0, 1000)));
        TestKit.check("negative maxRequests rejected", throwsIllegalArgument(() -> new SlidingWindowRateLimiter(-1, 1000)));
        TestKit.check("zero windowMillis rejected", throwsIllegalArgument(() -> new SlidingWindowRateLimiter(5, 0)));
        TestKit.check("negative windowMillis rejected", throwsIllegalArgument(() -> new SlidingWindowRateLimiter(5, -100)));
    }

    /**
     * Hammers a shared limiter with a small maxRequests from many threads concurrently, within
     * a window wide enough that it will not expire mid-test, and asserts the total number of
     * admitted requests never exceeds maxRequests. A limiter with a race condition in its
     * check-then-add logic could admit more than the configured limit.
     */
    private static void testConcurrencyNeverExceedsMaxRequestsWithinWindow() throws InterruptedException {
        int maxRequests = 50;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, 60_000);
        int threadCount = 20;
        int attemptsPerThread = 100;
        AtomicInteger admitted = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        Runnable task = () -> {
            for (int i = 0; i < attemptsPerThread; i++) {
                if (limiter.tryAcquire()) {
                    admitted.incrementAndGet();
                }
            }
        };
        for (int i = 0; i < threadCount; i++) {
            pool.submit(task);
        }
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);

        TestKit.check("all threads finished within timeout", finished);
        TestKit.check("admitted count equals exactly maxRequests under contention",
                admitted.get() == maxRequests);
        TestKit.check("currentCount matches admitted count", limiter.currentCount() == maxRequests);
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static boolean throwsIllegalArgument(ThrowingRunnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
