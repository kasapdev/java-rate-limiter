package dev.kasapdev.ratelimiter;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe leaky-bucket rate limiter.
 *
 * <p>The bucket starts empty and each admitted request raises its fill level by one unit, up to
 * a fixed {@code capacity}. The bucket continuously leaks (drains) at {@code leakRatePerSecond}
 * units per second, based on elapsed wall-clock time measured with {@link System#nanoTime()}.
 * {@link #tryAcquire()} admits the request (and raises the level by one) only if the bucket has
 * room for it once the leak owed for elapsed time has been applied.
 *
 * <p>As with {@link TokenBucketRateLimiter}, leak state is only recomputed lazily, on each call
 * that needs it, under a lock — there is no background thread or scheduler.
 */
public final class LeakyBucketRateLimiter {

    private final int capacity;
    private final double leakRatePerSecond;
    private final ReentrantLock lock = new ReentrantLock();

    /** Current fill level of the bucket; 0 means empty, {@code capacity} means full. */
    private double currentLevel;
    private long lastLeakNanos;

    /**
     * @param capacity          maximum number of requests the bucket can hold before it starts
     *                          rejecting further requests
     * @param leakRatePerSecond rate at which the bucket drains, in units per second
     * @throws IllegalArgumentException if capacity is not positive or the leak rate is negative
     */
    public LeakyBucketRateLimiter(int capacity, double leakRatePerSecond) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        if (leakRatePerSecond < 0) {
            throw new IllegalArgumentException("leakRatePerSecond must not be negative, got " + leakRatePerSecond);
        }
        this.capacity = capacity;
        this.leakRatePerSecond = leakRatePerSecond;
        this.currentLevel = 0;
        this.lastLeakNanos = System.nanoTime();
    }

    /**
     * Attempts to admit one request into the bucket. Applies any leak owed for elapsed time
     * first, then either raises the fill level by one (if there is room for a full additional
     * unit) or leaves the bucket untouched and returns {@code false}.
     *
     * <p>The room check is {@code currentLevel + 1 <= capacity} rather than
     * {@code currentLevel < capacity}: each admitted request occupies one whole unit, so even a
     * level that is a hair under {@code capacity} (e.g. due to floating-point leak residue from
     * a near-zero elapsed time) must not admit another request — doing so would push the level
     * past {@code capacity}, violating the bucket's fixed-capacity invariant.
     *
     * @return {@code true} if the request was admitted, {@code false} if the bucket is full
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            leak();
            if (currentLevel + 1 <= capacity) {
                currentLevel += 1;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the current fill level of the bucket, rounded up to the nearest whole unit, after
     *         applying any leak owed for elapsed time
     */
    public int currentLevel() {
        lock.lock();
        try {
            leak();
            return (int) Math.ceil(currentLevel);
        } finally {
            lock.unlock();
        }
    }

    /** Must be called while holding {@link #lock}. */
    private void leak() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastLeakNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double leakAmount = elapsedSeconds * leakRatePerSecond;
        if (leakAmount > 0) {
            currentLevel = Math.max(0, currentLevel - leakAmount);
        }
        lastLeakNanos = now;
    }
}
