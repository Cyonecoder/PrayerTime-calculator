package io.github.cyonecoder.salatapi.habous.client;

import java.util.Optional;

/**
 * Raised when the Habous endpoint is unreachable or returns a non-success status.
 *
 * <p>Carries the HTTP status when there was one, so the retry policy can tell a
 * temporary failure (429, 502, 503, 504) from a permanent one (404, 403) without
 * re-parsing a message string.
 *
 * <p>An outage must be survivable: the daily sync records the city as failed and
 * continues to the next, and the API serves whatever was already archived.
 */
public class HabousUnavailableException extends RuntimeException {

    private final Integer statusCode;

    public HabousUnavailableException(String message) {
        this(message, null, null);
    }

    public HabousUnavailableException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public HabousUnavailableException(String message, Integer statusCode) {
        this(message, statusCode, null);
    }

    public HabousUnavailableException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** @return the HTTP status, when the failure was a response rather than a transport error */
    public Optional<Integer> statusCode() {
        return Optional.ofNullable(statusCode);
    }
}
