package dev.k9o;

/**
 * Retry + backoff policy. Applied to idempotent methods
 * (GET/HEAD/OPTIONS/DELETE) on transient failures only (transport error /
 * 502 / 503 / 504 / 429); semantic 4xx and non-idempotent methods are
 * never retried.
 */
public final class RetryConfig {
    public final int maxRetries;
    /** "exponential" | "linear" | "none". */
    public final String backoff;
    public final int initialDelayMs;
    public final int maxDelayMs;

    public RetryConfig(int maxRetries, String backoff, int initialDelayMs, int maxDelayMs) {
        this.maxRetries = maxRetries;
        this.backoff = backoff;
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /** defaults: 3 retries, exponential, 100ms initial, 5000ms cap. */
    public static RetryConfig defaults() {
        return new RetryConfig(3, "exponential", 100, 5000);
    }
}
