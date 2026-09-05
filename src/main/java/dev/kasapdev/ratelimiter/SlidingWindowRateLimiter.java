package dev.kasapdev.ratelimiter;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe sliding-window rate limiter.
 *
 * <p>Keeps a log of the timestamps (in milliseconds, from {@link System#currentTimeMillis()})
 * of recent successful requests. On each {@link #tryAcquire()} call, timestamps older than
 * {@code windowMillis} are pruned from the front of the log, and the request is admitted only
 * if fewer than {@code maxRequests} timestamps remain in the window.
 */
public final class SlidingWindowRateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentLinkedDeque<Long> timestamps = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger(0);
    private final Object admitLock = new Object();

    /**
     * @param maxRequests  maximum number of requests allowed within the sliding window
     * @param windowMillis width of the sliding window, in milliseconds
     * @throws IllegalArgumentException if maxRequests or windowMillis is not positive
     */
    public SlidingWindowRateLimiter(int maxRequests, long windowMillis) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive, got " + maxRequests);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, got " + windowMillis);
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * Attempts to record a request now. Prunes expired timestamps first, then admits the
     * request only if doing so would not exceed {@code maxRequests} within the window.
     *
     * <p>The admission check-and-record is performed under an internal lock to make the
     * limiter safe under concurrent use: without it, multiple threads could each observe
     * fewer than {@code maxRequests} entries and all be admitted, overshooting the limit.
     *
     * @return {@code true} if the request was admitted, {@code false} if it was rejected
     */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        pruneExpired(now);
        synchronized (admitLock) {
            pruneExpired(now);
            if (size.get() < maxRequests) {
                timestamps.addLast(now);
                size.incrementAndGet();
                return true;
            }
            return false;
        }
    }

    /** @return the number of requests currently counted within the window */
    public int currentCount() {
        pruneExpired(System.currentTimeMillis());
        return size.get();
    }

    private void pruneExpired(long now) {
        long cutoff = now - windowMillis;
        Long head;
        while ((head = timestamps.peekFirst()) != null && head <= cutoff) {
            if (timestamps.removeFirstOccurrence(head)) {
                size.decrementAndGet();
            }
        }
    }
}
