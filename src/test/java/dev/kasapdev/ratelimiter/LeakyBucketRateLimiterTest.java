package dev.kasapdev.ratelimiter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LeakyBucketRateLimiterTest {

    public static void main(String[] args) throws Exception {
        testInitialStateEmptyAndBasicAcquire();
        testExhaustionRejectsFurtherRequests();
        testLeakOverTime();
        testInvalidConstructorArgsRejected();
        testCurrentLevelNeverExceedsCapacity();
        testTightLoopFillNeverAdmitsMoreThanCapacity();
        testConcurrencyNoLeakNeverExceedsCapacity();
        testConcurrencyWithLeakNeverExceedsBucketMath();

        TestKit.finish();
    }

    private static void testInitialStateEmptyAndBasicAcquire() {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(10, 0.0);
        TestKit.check("starts empty", limiter.currentLevel() == 0);
        TestKit.check("first acquire admitted", limiter.tryAcquire());
        TestKit.check("level rises by one after an admitted request", limiter.currentLevel() == 1);
    }

    private static void testExhaustionRejectsFurtherRequests() {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(5, 0.0);
        for (int i = 0; i < 5; i++) {
            TestKit.check("request " + (i + 1) + " of 5 admitted while filling the bucket", limiter.tryAcquire());
        }
        TestKit.check("bucket is exactly full after filling to capacity", limiter.currentLevel() == 5);
        TestKit.check("further acquire on a full bucket (no leak) fails", !limiter.tryAcquire());
        TestKit.check("bucket level unaffected by a rejected acquire", limiter.currentLevel() == 5);
    }

    /**
     * Fills the bucket to capacity, lets a short, real amount of wall-clock time pass, and then
     * checks the resulting level against what the leak-rate math allows for the elapsed time —
     * the same "measure real elapsed nanos, derive an expected bound from it" approach the
     * existing token-bucket concurrency test (testConcurrencyWithRefillNeverExceedsBucketMath)
     * uses, rather than assuming a sleep call slept for exactly its requested duration.
     */
    private static void testLeakOverTime() throws InterruptedException {
        int capacity = 10;
        double leakRatePerSecond = 100.0;
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(capacity, leakRatePerSecond);
        for (int i = 0; i < capacity; i++) {
            limiter.tryAcquire();
        }
        TestKit.check("bucket full after filling to capacity", limiter.currentLevel() == capacity);

        long startNanos = System.nanoTime();
        Thread.sleep(30);
        int levelAfter = limiter.currentLevel();
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;

        // The measurement window (startNanos .. now) can only be longer than the time the
        // limiter itself actually saw internally, so the leak computed from it is an upper
        // bound on how much could really have drained — the observed level can never be lower
        // than capacity minus that upper bound. A small epsilon absorbs scheduling jitter.
        double maxPossibleLeak = leakRatePerSecond * elapsedSeconds;
        double minExpectedLevel = Math.max(0, capacity - maxPossibleLeak) - 1;
        TestKit.check(
                "level after elapsed time never drops below what the leak rate could account for "
                        + "(got " + levelAfter + ", min expected " + minExpectedLevel + ")",
                levelAfter >= minExpectedLevel);
        TestKit.check(
                "some leaking actually happened after 30ms at 100 units/sec (got " + levelAfter + ")",
                levelAfter < capacity);
        TestKit.check("level never exceeds capacity", levelAfter <= capacity);
    }

    private static void testInvalidConstructorArgsRejected() {
        TestKit.check("zero capacity rejected", throwsIllegalArgument(() -> new LeakyBucketRateLimiter(0, 1.0)));
        TestKit.check("negative capacity rejected", throwsIllegalArgument(() -> new LeakyBucketRateLimiter(-1, 1.0)));
        TestKit.check("negative leak rate rejected", throwsIllegalArgument(() -> new LeakyBucketRateLimiter(10, -1.0)));
    }

    private static void testCurrentLevelNeverExceedsCapacity() throws InterruptedException {
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(3, 0.0);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire();
        }
        Thread.sleep(20);
        TestKit.check("level never exceeds capacity even without any leak", limiter.currentLevel() <= 3);
    }

    /**
     * Hammers a nonzero-leak-rate bucket with back-to-back {@code tryAcquire()} calls and no
     * sleeping in between, so a tiny, real amount of wall-clock time still elapses between
     * calls and leaks an infinitesimal amount off the fill level. Regression test for a bug
     * where the admission check used {@code currentLevel < capacity} instead of
     * {@code currentLevel + 1 <= capacity}: a level that had leaked to a hair under capacity
     * (e.g. 9.9999999997 for a capacity of 10) satisfied the old check and then had a full unit
     * added on top, pushing the level past capacity and admitting one request too many.
     */
    private static void testTightLoopFillNeverAdmitsMoreThanCapacity() {
        int capacity = 10;
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(capacity, 5.0);
        int admitted = 0;
        for (int i = 0; i < capacity + 50; i++) {
            if (limiter.tryAcquire()) {
                admitted++;
            }
        }
        TestKit.check(
                "tight-loop admission never exceeds capacity even with tiny real leaks between calls "
                        + "(admitted " + admitted + " of " + (capacity + 50) + " attempts)",
                admitted <= capacity);
        TestKit.check("fill level never exceeds capacity after the tight-loop fill",
                limiter.currentLevel() <= capacity);
    }

    /**
     * With leaking disabled, the bucket can never admit more requests than its capacity, no
     * matter how many threads race to fill it. This is a genuine correctness property of the
     * lock-protected leak+admit logic, not a timing-sensitive heuristic.
     */
    private static void testConcurrencyNoLeakNeverExceedsCapacity() throws InterruptedException {
        int capacity = 100;
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(capacity, 0.0);
        int threadCount = 16;
        int attemptsPerThread = 200; // threadCount * attemptsPerThread >> capacity
        AtomicInteger successes = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        Runnable task = () -> {
            for (int i = 0; i < attemptsPerThread; i++) {
                if (limiter.tryAcquire()) {
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
        TestKit.check("total admitted requests equals exactly the capacity",
                successes.get() == capacity);
        TestKit.check("bucket left exactly full after being filled by concurrent load",
                limiter.currentLevel() == capacity);
    }

    /**
     * With a nonzero leak rate, concurrently hammering the bucket must still never admit more
     * requests in total than "capacity + units leaked over the wall-clock duration of the
     * test", which is the theoretical maximum the bucket math allows: the fill level can never
     * exceed capacity at any instant, so total admissions can never exceed capacity plus
     * whatever has drained away by the time the test ends. A buggy (non-thread-safe)
     * implementation could double-admit requests and blow past this bound.
     */
    private static void testConcurrencyWithLeakNeverExceedsBucketMath() throws InterruptedException {
        int capacity = 5;
        double leakRatePerSecond = 50.0;
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(capacity, leakRatePerSecond);
        int threadCount = 20;
        long testDurationMillis = 150;
        AtomicInteger successes = new AtomicInteger(0);

        long startNanos = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        long deadline = System.currentTimeMillis() + testDurationMillis;
        Runnable task = () -> {
            while (System.currentTimeMillis() < deadline) {
                if (limiter.tryAcquire()) {
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
        // the limiter's own internal nanoTime-based leak accounting.
        double maxAllowed = capacity + leakRatePerSecond * elapsedSeconds + 5;

        TestKit.check("some acquisitions succeeded", successes.get() > 0);
        TestKit.check("total admitted requests never exceeds bucket math upper bound "
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
