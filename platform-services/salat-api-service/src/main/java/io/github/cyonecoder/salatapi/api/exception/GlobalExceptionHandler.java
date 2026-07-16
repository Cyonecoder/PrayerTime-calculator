package io.github.cyonecoder.salatapi.api.exception;

import io.github.cyonecoder.salatapi.api.dto.response.ApiError;
import io.github.cyonecoder.salatapi.api.filter.CorrelationIdFilter;
import io.github.cyonecoder.salatapi.secapproach.exception.CityNotSupportedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into a uniform {@link ApiError} body with the right status.
 *
 * <p>The mapping is deliberate:
 * <ul>
 *   <li>a missing city, month or report → 404;
 *   <li>a city that exists but has no sec-approach offsets → 422 (the request is
 *       well-formed, but the city cannot be served this way — never a silent
 *       fallback);
 *   <li>a bad date, precision or range → 400.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> noSuchElement(NoSuchElementException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    /**
     * A city that exists but has no sec-approach offsets is 422 Unprocessable
     * Entity: the request is understood, but this city cannot be served by
     * sec-approach, and the service refuses rather than guessing.
     */
    @ExceptionHandler(CityNotSupportedException.class)
    public ResponseEntity<ApiError> notSupported(CityNotSupportedException e, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), request);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> badRequest(Exception e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error for {}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        Object correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        ApiError body = new ApiError(
                status.value(), status.getReasonPhrase(), message, request.getRequestURI(),
                correlationId == null ? null : correlationId.toString(), clock.instant());
        return ResponseEntity.status(status).body(body);
    }
}
