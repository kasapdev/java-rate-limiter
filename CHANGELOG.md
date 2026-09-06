# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.2.0] - 2026-09-06

### Added

- `TokenBucketRateLimiter.tryAcquire(int cost, long timeout, TimeUnit unit)` — a blocking
  variant of `tryAcquire` that waits (without busy-looping) for enough tokens to refill, up to
  the given timeout, instead of failing fast. Computes the exact sleep interval needed from the
  refill rate and returns `false` immediately, without waiting out the timeout, for requests
  that could never succeed (cost greater than capacity, or a zero refill rate with too few
  tokens already available).

## [1.1.0] - 2026-09-06

### Added

- Test coverage for deterministic edge cases in both rate limiters:
  - `TokenBucketRateLimiter`: a `tryAcquire()` cost greater than the
    bucket's capacity is always rejected (even on a full bucket) and
    never mutates the available token count.
  - `TokenBucketRateLimiter`: acquiring exactly the full capacity in a
    single call succeeds and leaves the bucket at exactly 0.
  - `SlidingWindowRateLimiter`: a brand-new limiter with no requests yet
    reports `currentCount() == 0`.
  - `SlidingWindowRateLimiter`: a rejected attempt (window full) does not
    change `currentCount()`.

No behavioral changes were needed — all new edge-case tests passed against
the existing implementation.
