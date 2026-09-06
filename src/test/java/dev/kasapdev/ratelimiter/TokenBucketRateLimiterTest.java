package dev.kasapdev.ratelimiter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TokenBucketRateLimiterTest {

    public static void main(String[] args) throws Exception {
        testInitialCapacityAndBasicAcquire();
        testExhaustionRejectsFurtherRequests();
        testRefillOverTime();
        testInvalidConstructorArgsRejected();
        testInvalidCostRejected();
        testAvailableTokensNeverExceedsCapacity();
        testConcurrencyNoRefillNeverExceedsCapacity();
        testConcurrencyWithRefillNeverExceedsBucketMath();
        testCostGreaterThanCapacityAlwaysRejected();
        testAcquiringExactlyCapacityInOneCallSucceeds();
        testBlockingAcquireSucceedsImmediatelyWhenTokensAvailable();
        testBlockingAcquireWaitsForRefillThenSucceeds();
        testBlockingAcquireTimesOutWhenRefillTooSlow();
        testBlockingAcquireRejectsCostGreaterThanCapacityImmediately();
        testBlockingAcquireRejectsZeroRefillWithInsufficientTokensImmediately();
        testBlockingAcquireInvalidArgsRejected();

        TestKit.finish();
    }

    private static void testCostGreaterThanCapacityAlwaysRejected() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 0.0);
        TestKit.check(
                "a cost greater than capacity is rejected even on a full bucket",
                !limiter.tryAcquire(6));
        TestKit.check(
                "a rejected over-capacity request does not mutate the available tokens",
                limiter.availableTokens() == 5);
    }

    private static void testAcquiringExactlyCapacityInOneCallSucceeds() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(7, 0.0);
        TestKit.check("acquiring exactly the full capacity in one call succeeds", limiter.tryAcquire(7));
        TestKit.check("bucket is exactly empty after acquiring exactly its capacity", limiter.availableTokens() == 0);
    }

    private static void testInitialCapacityAndBasicAcquire() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 0.0);
        TestKit.check("starts at full capacity", limiter.availableTokens() == 10);
        TestKit.check("acquire within capacity succeeds", limiter.tryAcquire(4));
        TestKit.check("available tokens decremented by cost", limiter.availableTokens() == 6);
        TestKit.check("acquire remaining tokens succeeds", limiter.tryAcquire(6));
        TestKit.check("bucket now empty", limiter.availableTokens() == 0);
    }

    private static void testExhaustionRejectsFurtherRequests() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 0.0);
        TestKit.check("first acquire of 5 succeeds", limiter.tryAcquire(5));
        TestKit.check("further acquire on empty bucket fails", !limiter.tryAcquire(1));
        TestKit.check("bucket state unaffected by failed acquire", limiter.availableTokens() == 0);
    }

    private static void testRefillOverTime() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 100.0); // 100 tokens/sec
        TestKit.check("drain the bucket", limiter.tryAcquire(10));
        TestKit.check("empty immediately after drain", limiter.availableTokens() == 0);
        Thread.sleep(150); // ~15 tokens worth of refill, capped at capacity
        TestKit.check("bucket refills up to capacity after enough elapsed time", limiter.availableTokens() == 10);
    }

    private static void testInvalidConstructorArgsRejected() throws InterruptedException {
        TestKit.check("zero capacity rejected", throwsIllegalArgument(() -> new TokenBucketRateLimiter(0, 1.0)));
        TestKit.check("negative capacity rejected", throwsIllegalArgument(() -> new TokenBucketRateLimiter(-1, 1.0)));
        TestKit.check("negative refill rate rejected", throwsIllegalArgument(() -> new TokenBucketRateLimiter(10, -1.0)));
    }

    private static void testInvalidCostRejected() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1.0);
        TestKit.check("zero cost rejected", throwsIllegalArgument(() -> limiter.tryAcquire(0)));
        TestKit.check("negative cost rejected", throwsIllegalArgument(() -> limiter.tryAcquire(-5)));
    }

    private static void testAvailableTokensNeverExceedsCapacity() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1000.0);
        Thread.sleep(50);
        TestKit.check("available tokens capped at capacity despite fast refill", limiter.availableTokens() == 3);
    }

    /**
     * With refill disabled, the bucket can never grant more successful acquisitions than its
     * starting capacity, no matter how many threads race for tokens. This is a genuine
     * correctness property of the lock-protected refill+deduct logic, not a timing-sensitive
     * heuristic.
     */
    private static void testConcurrencyNoRefillNeverExceedsCapacity() throws InterruptedException {
        int capacity = 100;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 0.0);
        int threadCount = 16;
        int attemptsPerThread = 200; // threadCount * attemptsPerThread >> capacity
        AtomicInteger successes = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        Runnable task = () -> {
            for (int i = 0; i < attemptsPerThread; i++) {
                if (limiter.tryAcquire(1)) {
                    successes.incrementAndGet();
                }
            }
        };
        for (int i = 0; i < threadCount; i++) {
            pool.submit(task);
        }
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);
        TestKit.check("all threads finished within timeout", finished);
        TestKit.check("total successful acquisitions equals exactly the starting capacity",
                successes.get() == capacity);
        TestKit.check("bucket left empty after being exhausted by concurrent load",
                limiter.availableTokens() == 0);
    }

    /**
     * With a nonzero refill rate, concurrently hammering the bucket must still never grant more
     * tokens in total than "starting capacity + tokens refilled over the wall-clock duration of
     * the test", which is the theoretical maximum the bucket math allows. A buggy
     * (non-thread-safe) implementation could double-grant tokens and blow past this bound.
     */
    private static void testConcurrencyWithRefillNeverExceedsBucketMath() throws InterruptedException {
        int capacity = 5;
        double refillPerSecond = 50.0;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillPerSecond);
        int threadCount = 20;
        long testDurationMillis = 150;
        AtomicInteger successes = new AtomicInteger(0);

        long startNanos = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        long deadline = System.currentTimeMillis() + testDurationMillis;
        Runnable task = () -> {
            while (System.currentTimeMillis() < deadline) {
                if (limiter.tryAcquire(1)) {
                    successes.incrementAndGet();
                }
            }
        };
        for (int i = 0; i < threadCount; i++) {
            pool.submit(task);
        }
        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);
        long endNanos = System.nanoTime();
        TestKit.check("all threads finished within timeout", finished);

        double elapsedSeconds = (endNanos - startNanos) / 1_000_000_000.0;
        // Generous epsilon to absorb scheduling jitter between measuring elapsed time here and
        // the limiter's own internal nanoTime-based refill accounting.
        double maxAllowed = capacity + refillPerSecond * elapsedSeconds + 5;

        TestKit.check("some acquisitions succeeded", successes.get() > 0);
        TestKit.check("total successful acquisitions never exceeds bucket math upper bound "
                        + "(got " + successes.get() + ", max allowed " + maxAllowed + ")",
                successes.get() <= maxAllowed);
    }

    private static void testBlockingAcquireSucceedsImmediatelyWhenTokensAvailable() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 0.0);
        long start = System.nanoTime();
        boolean acquired = limiter.tryAcquire(3, 1, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        TestKit.check("blocking acquire succeeds immediately when tokens are already available", acquired);
        TestKit.check("blocking acquire did not wait when tokens were already available",
                elapsedMillis < 500);
        TestKit.check("blocking acquire deducted the requested cost", limiter.availableTokens() == 7);
    }

    private static void testBlockingAcquireWaitsForRefillThenSucceeds() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 100.0); // 100 tokens/sec
        TestKit.check("drain the bucket before blocking-acquire test", limiter.tryAcquire(5));

        long start = System.nanoTime();
        boolean acquired = limiter.tryAcquire(1, 500, TimeUnit.MILLISECONDS);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        TestKit.check("blocking acquire succeeds once enough time passes for refill", acquired);
        TestKit.check("blocking acquire returned well before the full timeout elapsed "
                        + "(took " + elapsedMillis + "ms)",
                elapsedMillis < 400);
    }

    private static void testBlockingAcquireTimesOutWhenRefillTooSlow() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0); // 1 token/sec: far too slow
        TestKit.check("drain the bucket before timeout test", limiter.tryAcquire(5));

        long start = System.nanoTime();
        boolean acquired = limiter.tryAcquire(5, 60, TimeUnit.MILLISECONDS);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        TestKit.check("blocking acquire times out when the refill rate can't satisfy it in time",
                !acquired);
        TestKit.check("blocking acquire waited roughly the full timeout before giving up "
                        + "(took " + elapsedMillis + "ms)",
                elapsedMillis >= 50);
    }

    private static void testBlockingAcquireRejectsCostGreaterThanCapacityImmediately() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 10.0);
        long start = System.nanoTime();
        boolean acquired = limiter.tryAcquire(6, 2, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        TestKit.check("blocking acquire rejects a cost greater than capacity", !acquired);
        TestKit.check("blocking acquire does not wait out the timeout for an impossible request "
                        + "(took " + elapsedMillis + "ms)",
                elapsedMillis < 500);
    }

    private static void testBlockingAcquireRejectsZeroRefillWithInsufficientTokensImmediately() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 0.0);
        TestKit.check("drain the zero-refill bucket before the test", limiter.tryAcquire(5));

        long start = System.nanoTime();
        boolean acquired = limiter.tryAcquire(1, 2, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        TestKit.check("blocking acquire rejects immediately when refill rate is zero and tokens are insufficient",
                !acquired);
        TestKit.check("blocking acquire does not wait out the timeout when waiting can never help "
                        + "(took " + elapsedMillis + "ms)",
                elapsedMillis < 500);
    }

    private static void testBlockingAcquireInvalidArgsRejected() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1.0);
        TestKit.check("zero cost rejected for blocking acquire",
                throwsIllegalArgument(() -> limiter.tryAcquire(0, 100, TimeUnit.MILLISECONDS)));
        TestKit.check("negative cost rejected for blocking acquire",
                throwsIllegalArgument(() -> limiter.tryAcquire(-5, 100, TimeUnit.MILLISECONDS)));
        TestKit.check("negative timeout rejected for blocking acquire",
                throwsIllegalArgument(() -> limiter.tryAcquire(1, -1, TimeUnit.MILLISECONDS)));
    }

    private interface ThrowingRunnable {
        void run() throws InterruptedException;
    }

    private static boolean throwsIllegalArgument(ThrowingRunnable r) throws InterruptedException {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
