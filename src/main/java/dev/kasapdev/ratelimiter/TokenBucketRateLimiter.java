package dev.kasapdev.ratelimiter;

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
