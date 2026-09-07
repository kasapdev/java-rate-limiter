# java-rate-limiter

[![CI](https://github.com/kasapdev/java-rate-limiter/actions/workflows/ci.yml/badge.svg)](https://github.com/kasapdev/java-rate-limiter/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE) ![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)

A zero-dependency Java library providing three thread-safe rate limiting strategies:
`TokenBucketRateLimiter` (classic token bucket with continuous wall-clock refill),
`SlidingWindowRateLimiter` (fixed request cap within a rolling time window), and
`LeakyBucketRateLimiter` (fixed-capacity bucket that drains at a constant rate). Pure Java 17,
no external libraries, no build tool required.

## Build & Run

```bash
JAVAC="/path/to/jdk/bin/javac"
JAVA="/path/to/jdk/bin/java"

# Compile the library
"$JAVAC" -d out $(find src/main/java -name "*.java")

# Compile the tests against the compiled library
"$JAVAC" -cp out -d out $(find src/test/java -name "*.java")

# Run the tests
"$JAVA" -cp out dev.kasapdev.ratelimiter.TokenBucketRateLimiterTest
"$JAVA" -cp out dev.kasapdev.ratelimiter.SlidingWindowRateLimiterTest
"$JAVA" -cp out dev.kasapdev.ratelimiter.LeakyBucketRateLimiterTest
```

On Windows, replace `$(find ... -name "*.java")` with an explicit file list, or run the
`find` substitution from Git Bash / WSL.

## Usage

```java
import dev.kasapdev.ratelimiter.TokenBucketRateLimiter;
import dev.kasapdev.ratelimiter.SlidingWindowRateLimiter;
import dev.kasapdev.ratelimiter.LeakyBucketRateLimiter;

import java.util.concurrent.TimeUnit;

public class Example {
    public static void main(String[] args) throws InterruptedException {
        // Allow bursts up to 20 requests, refilling at 5 tokens/second.
        TokenBucketRateLimiter bucket = new TokenBucketRateLimiter(20, 5.0);
        if (bucket.tryAcquire(1)) {
            // handle request
        } else {
            // reject: too many requests
        }
        System.out.println("Tokens left: " + bucket.availableTokens());

        // Block for up to 500ms waiting for a token instead of failing fast.
        if (bucket.tryAcquire(1, 500, TimeUnit.MILLISECONDS)) {
            // handle request
        } else {
            // gave up waiting: still no tokens after 500ms
        }

        // Allow at most 100 requests per 60-second rolling window.
        SlidingWindowRateLimiter window = new SlidingWindowRateLimiter(100, 60_000);
        if (window.tryAcquire()) {
            // handle request
        }

        // Smooth out bursts: queue up to 10 requests, draining at 2 requests/second.
        LeakyBucketRateLimiter leaky = new LeakyBucketRateLimiter(10, 2.0);
        for (int i = 0; i < 15; i++) {
            if (leaky.tryAcquire()) {
                System.out.println("request " + i + " admitted, level=" + leaky.currentLevel());
            } else {
                System.out.println("request " + i + " rejected: bucket full");
            }
        }
    }
}
```

## Leaky Bucket Rate Limiter

`LeakyBucketRateLimiter` models a fixed-capacity bucket that starts empty. Each admitted
request raises the bucket's fill level by one, up to `capacity`; the bucket continuously
leaks (drains) at a constant `leakRatePerSecond`, computed lazily from elapsed wall-clock
time (`System.nanoTime()`) on every call — there is no background thread. This makes it a
good fit for smoothing bursty traffic into a steady downstream rate, as opposed to
`TokenBucketRateLimiter`, which allows short bursts up to its full capacity as long as
tokens are available.

```java
import dev.kasapdev.ratelimiter.LeakyBucketRateLimiter;

public class LeakyBucketExample {
    public static void main(String[] args) {
        // Queue up to 10 requests; the queue drains at 2 requests/second.
        LeakyBucketRateLimiter limiter = new LeakyBucketRateLimiter(10, 2.0);

        if (limiter.tryAcquire()) {
            // request admitted: forward it downstream at the leak rate
        } else {
            // bucket is full: reject or shed this request
        }

        System.out.println("Current fill level: " + limiter.currentLevel());
    }
}
```

## API

### `TokenBucketRateLimiter`

- `TokenBucketRateLimiter(int capacity, double refillTokensPerSecond)` — creates a bucket
  starting full at `capacity`, refilling continuously at the given rate.
- `boolean tryAcquire(int cost)` — refills based on elapsed time, then attempts to deduct
  `cost` tokens; returns whether it succeeded. Thread-safe.
- `boolean tryAcquire(int cost, long timeout, TimeUnit unit)` — blocks until `cost` tokens can
  be acquired or `timeout` elapses, whichever comes first. Sleeps for computed intervals
  instead of busy-waiting, and returns `false` immediately (without waiting out the timeout) if
  the request could never succeed, e.g. `cost` exceeds `capacity`.
- `int availableTokens()` — current token count (after applying any owed refill), floored to
  an integer.

### `SlidingWindowRateLimiter`

- `SlidingWindowRateLimiter(int maxRequests, long windowMillis)` — allows at most
  `maxRequests` admitted calls within any trailing `windowMillis` period.
- `boolean tryAcquire()` — prunes expired timestamps and admits the request if the window
  isn't full; thread-safe under concurrent callers.
- `int currentCount()` — number of requests currently counted within the window.

### `LeakyBucketRateLimiter`

- `LeakyBucketRateLimiter(int capacity, double leakRatePerSecond)` — creates a bucket
  starting empty at level 0, with room for `capacity` requests, draining continuously at the
  given rate.
- `boolean tryAcquire()` — applies any leak owed for elapsed time, then admits the request
  (raising the level by one) if there's room; returns whether it was admitted. Thread-safe.
- `int currentLevel()` — current fill level (after applying any owed leak), rounded up to an
  integer.

## License

MIT — see [LICENSE](LICENSE).
