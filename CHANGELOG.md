# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
