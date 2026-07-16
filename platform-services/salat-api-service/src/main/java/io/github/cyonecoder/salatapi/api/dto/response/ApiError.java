package io.github.cyonecoder.salatapi.api.dto.response;

import java.time.Instant;

/**
 * The uniform error body returned by {@code GlobalExceptionHandler}.
 *
 * @param status        the HTTP status code
 * @param error         the HTTP reason phrase
 * @param message       a human-readable explanation
 * @param path          the request path
 * @param correlationId the request's correlation id, for tracing
 * @param timestamp     when the error was produced
 */
public record ApiError(
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        Instant timestamp) {
}
