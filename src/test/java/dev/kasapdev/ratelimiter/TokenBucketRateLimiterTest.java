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

        TestKit.finish();
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

    private static void testInvalidConstructorArgsRejected() {
        TestKit.check("zero capacity rejected", throwsIllegalArgument(() -> new TokenBucketRateLimiter(0, 1.0)));
        TestKit.check("negative capacity rejected", throwsIllegalArgument(() -> new TokenBucketRateLimiter(-1, 1.0)));
        TestKit.check("negative refill rate rejected", throwsIllegalArgument(() -> new TokenBucketRateLimiter(10, -1.0)));
    }

    private static void testInvalidCostRejected() {
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
