# java-rate-limiter

[![CI](https://github.com/kasapdev/java-rate-limiter/actions/workflows/ci.yml/badge.svg)](https://github.com/kasapdev/java-rate-limiter/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE) ![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)

A zero-dependency Java library providing two thread-safe rate limiting strategies:
`TokenBucketRateLimiter` (classic token bucket with continuous wall-clock refill) and
`SlidingWindowRateLimiter` (fixed request cap within a rolling time window). Pure Java 17,
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
```

On Windows, replace `$(find ... -name "*.java")` with an explicit file list, or run the
`find` substitution from Git Bash / WSL.

## Usage

```java
import dev.kasapdev.ratelimiter.TokenBucketRateLimiter;
import dev.kasapdev.ratelimiter.SlidingWindowRateLimiter;

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

## License

MIT — see [LICENSE](LICENSE).
