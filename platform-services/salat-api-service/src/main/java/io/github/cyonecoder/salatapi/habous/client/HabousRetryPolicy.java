package io.github.cyonecoder.salatapi.habous.client;

import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Decides which official-site failures may be retried, and how long to wait
 * (spec 14).
 *
 * <p>Only <strong>temporary</strong> failures are retried: timeouts, connection
 * resets, and the statuses that mean "busy, try later" — 429, 502, 503, 504. A
 * 404 or 403 is a permanent answer and retrying it just adds load. Parsing
 * failures are never retried at all: re-fetching an unchanged malformed page
 * cannot make it parse, so a retry loop there would hammer the site forever for
 * nothing.
 *
 * <p>Backoff is exponential with <em>full jitter</em>: the wait is a random value
 * in {@code [0, initial × 2^(attempt-1)]}, capped. Jitter matters because the
 * daily sync walks ~191 cities behind one worker — if the site has a wide outage,
 * undithered backoff would resynchronize every retry into a burst.
 */
@Component
public class HabousRetryPolicy {

    /** Statuses meaning "temporarily unavailable"; anything else is permanent. */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    private final HabousProperties properties;

    public HabousRetryPolicy(HabousProperties properties) {
        this.properties = properties;
    }

    /**
     * @param failure the failure raised by an attempt
     * @return whether another attempt is worth making
     */
    public boolean isRetryable(Throwable failure) {
        if (failure instanceof HabousUnavailableException unavailable) {
            return unavailable.statusCode()
                    .map(RETRYABLE_STATUSES::contains)
                    // No status: a transport error. Inspect the cause.
                    .orElseGet(() -> isRetryableTransport(unavailable.getCause()));
        }
        return isRetryableTransport(failure);
    }

    /** @param statusCode an HTTP status from the official site */
    public boolean isRetryableStatus(int statusCode) {
        return RETRYABLE_STATUSES.contains(statusCode);
    }

    /**
     * The wait before the given retry, using exponential backoff with full jitter.
     *
     * @param attempt      the attempt that just failed, 1-based
     * @param jitterFactor a value in {@code [0, 1)}; supplied so tests are deterministic
     * @return the delay before the next attempt
     */
    public Duration backoffFor(int attempt, double jitterFactor) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1 but was " + attempt);
        }
        long initialMillis = properties.retryInitialBackoff().toMillis();
        long capMillis = properties.retryMaxBackoff().toMillis();

        // Exponential, guarding against overflow on a large attempt count.
        long exponential = initialMillis;
        for (int i = 1; i < attempt && exponential < capMillis; i++) {
            exponential = Math.min(exponential * 2, capMillis);
        }
        long ceiling = Math.min(exponential, capMillis);
        return Duration.ofMillis((long) (ceiling * clamp(jitterFactor)));
    }

    /** @param attempt the attempt that just failed, 1-based */
    public Duration backoffFor(int attempt) {
        return backoffFor(attempt, ThreadLocalRandom.current().nextDouble());
    }

    /** @return the total attempts allowed per city, including the first */
    public int maxAttempts() {
        return properties.maxAttempts();
    }

    private static boolean isRetryableTransport(Throwable cause) {
        if (cause == null) {
            return false;
        }
        if (cause instanceof HttpTimeoutException
                || cause instanceof SocketTimeoutException
                || cause instanceof InterruptedIOException
                || cause instanceof ConnectException) {
            return true;
        }
        if (cause instanceof SocketException socket) {
            // "Connection reset" / "Broken pipe" — the peer dropped us mid-exchange.
            String message = socket.getMessage();
            return message == null || !message.toLowerCase(java.util.Locale.ROOT).contains("refused");
        }
        if (cause instanceof IOException) {
            String message = cause.getMessage();
            return message != null
                    && message.toLowerCase(java.util.Locale.ROOT).contains("connection reset");
        }
        return false;
    }

    private static double clamp(double jitterFactor) {
        if (jitterFactor < 0.0) {
            return 0.0;
        }
        return Math.min(jitterFactor, 1.0);
    }
}
