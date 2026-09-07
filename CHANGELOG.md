# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.3.0] - 2026-09-07

### Added

- `LeakyBucketRateLimiter` — a new rate limiting strategy, distinct from the existing
  token-bucket and sliding-window limiters. The bucket starts empty and each admitted
  `tryAcquire()` call raises its fill level by one, up to a fixed `capacity`; the bucket
  continuously leaks (drains) at a constant `leakRatePerSecond`, computed lazily from elapsed
  wall-clock time (`System.nanoTime()`) on every call, with no background thread — the same
  lazy-recompute approach `TokenBucketRateLimiter` uses for refill. `currentLevel()` reports
  the current fill level after applying any owed leak.
- Test coverage for `LeakyBucketRateLimiter`: basic fill/exhaustion behavior, leak-rate math
  asserted against real measured elapsed time (no long sleeps), invalid-argument rejection,
  and two concurrency tests (zero-leak exact-capacity admission, and nonzero-leak admissions
  bounded by capacity plus leaked-over-elapsed-time math) in the same style as the existing
  limiters' concurrency tests.

### Fixed

- `LeakyBucketRateLimiter.tryAcquire()` initially checked room with `currentLevel < capacity`,
  which could admit one request too many: a level that had leaked to a hair under `capacity`
  (floating-point residue from a near-zero elapsed time between back-to-back calls) satisfied
  that check and then had a full unit added on top, pushing the level past `capacity`. Fixed
  to check `currentLevel + 1 <= capacity` instead — caught by a dedicated tight-loop regression
  test before release.

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
