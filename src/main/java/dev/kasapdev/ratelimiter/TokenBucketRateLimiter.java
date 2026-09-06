package dev.kasapdev.ratelimiter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe token-bucket rate limiter.
 *
 * <p>The bucket starts full (at {@code capacity} tokens) and refills continuously at
 * {@code refillTokensPerSecond} tokens per second, based on elapsed wall-clock time measured
 * with {@link System#nanoTime()}. Refill state is only recomputed lazily, on each call that
 * needs it, under a lock — there is no background thread.
 */
public final class TokenBucketRateLimiter {

    private final int capacity;
    private final double refillTokensPerSecond;
    private final ReentrantLock lock = new ReentrantLock();

    /** Current token count, scaled up (see {@link #SCALE}) to retain fractional precision. */
    private double availableTokens;
    private long lastRefillNanos;

    /**
     * @param capacity              maximum number of tokens the bucket can hold; also the
     *                              initial number of tokens available
     * @param refillTokensPerSecond rate at which tokens are added back to the bucket
     * @throws IllegalArgumentException if capacity is not positive or the refill rate is negative
     */
    public TokenBucketRateLimiter(int capacity, double refillTokensPerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        if (refillTokensPerSecond < 0) {
            throw new IllegalArgumentException("refillTokensPerSecond must not be negative, got " + refillTokensPerSecond);
        }
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.availableTokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Attempts to acquire {@code cost} tokens from the bucket. Refills the bucket based on
     * elapsed time first, then either deducts the cost (if enough tokens are available) or
     * leaves the bucket untouched and returns {@code false}.
     *
     * @param cost number of tokens requested; must be positive
     * @return {@code true} if the tokens were acquired, {@code false} otherwise
     */
    public boolean tryAcquire(int cost) {
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive, got " + cost);
        }
        lock.lock();
        try {
            refill();
            if (availableTokens >= cost) {
                availableTokens -= cost;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until {@code cost} tokens can be acquired or {@code timeout} elapses, whichever
     * happens first.
     *
     * <p>Unlike {@link #tryAcquire(int)}, which fails immediately if the bucket does not
     * currently hold enough tokens, this method computes how long the bucket needs to refill
     * enough to grant the request and sleeps for (at most) that long before retrying. It never
     * busy-waits: each retry is preceded by a computed sleep, and the method returns as soon as
     * the request can be satisfied or the deadline passes.
     *
     * <p>If the request can never be satisfied — {@code cost} exceeds {@code capacity}, or the
     * bucket currently has too few tokens and the refill rate is {@code 0} — this returns
     * {@code false} immediately without waiting out the full timeout.
     *
     * @param cost    number of tokens requested; must be positive
     * @param timeout maximum time to wait for the tokens to become available; must not be negative
     * @param unit    the unit of {@code timeout}
     * @return {@code true} if the tokens were acquired before the timeout elapsed, {@code false}
     *         otherwise
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean tryAcquire(int cost, long timeout, TimeUnit unit) throws InterruptedException {
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive, got " + cost);
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative, got " + timeout);
        }
        if (cost > capacity) {
            // Can never be satisfied: the bucket can never hold more than `capacity` tokens.
            return false;
        }

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (true) {
            long waitNanos;
            lock.lock();
            try {
                refill();
                if (availableTokens >= cost) {
                    availableTokens -= cost;
                    return true;
                }
                double missing = cost - availableTokens;
                waitNanos = refillTokensPerSecond > 0
                        ? (long) Math.ceil(missing / refillTokensPerSecond * 1_000_000_000.0)
                        : -1; // refill rate is zero: waiting can never help
            } finally {
                lock.unlock();
            }

            if (waitNanos < 0) {
                return false;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }

            long sleepNanos = Math.min(waitNanos, remainingNanos);
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        }
    }

    /**
     * @return the current number of available tokens, rounded down to the nearest whole
     *         token, after applying any refill owed for elapsed time
     */
    public int availableTokens() {
        lock.lock();
        try {
            refill();
            return (int) Math.floor(availableTokens);
        } finally {
            lock.unlock();
        }
    }

    /** Must be called while holding {@link #lock}. */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double refillAmount = elapsedSeconds * refillTokensPerSecond;
        if (refillAmount > 0) {
            availableTokens = Math.min(capacity, availableTokens + refillAmount);
        }
        lastRefillNanos = now;
    }
}
