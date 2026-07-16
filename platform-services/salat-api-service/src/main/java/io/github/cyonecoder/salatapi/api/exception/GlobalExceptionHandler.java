package io.github.cyonecoder.salatapi.api.exception;

import io.github.cyonecoder.salatapi.api.filter.CorrelationIdFilter;
import io.github.cyonecoder.salatapi.secapproach.exception.CityNotSupportedException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.UncheckedIOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into RFC-7807 {@link ProblemDetail} responses with a stable
 * {@code code} property (spec 24).
 *
 * <p><strong>Nothing internal is ever exposed.</strong> Client-error messages come
 * from the (safe) exception text the service authored; server-error messages come
 * from the code's fixed default, never the underlying cause. Stack traces,
 * filesystem paths, package names, raw HTML and environment values never reach the
 * body — server-side causes are logged, not returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e, HttpServletRequest request) {
        return problem(e.code(), e.getMessage(), request, e.code().isServerError() ? e : null);
    }

    /** A sec-approach city that exists but has no offsets → 422, never a guess. */
    @ExceptionHandler(CityNotSupportedException.class)
    public ProblemDetail handleUnsupported(CityNotSupportedException e, HttpServletRequest request) {
        return problem(ApiErrorCode.SEC_APPROACH_CITY_UNSUPPORTED, e.getMessage(), request, null);
    }

    /** A malformed path or query value (e.g. a bad date or month). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        ApiErrorCode code = codeForParameter(e.getName(), e.getRequiredType());
        return problem(code, code.defaultDetail(), request, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(
            MissingServletRequestParameterException e, HttpServletRequest request) {
        ApiErrorCode code = codeForParameter(e.getParameterName(), null);
        return problem(code, "Required parameter '" + e.getParameterName() + "' is missing.", request, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        // Reached only for validation the service did not wrap in ApiException; the
        // message is service-authored and safe.
        return problem(ApiErrorCode.INVALID_DATE, e.getMessage(), request, null);
    }

    /** A stored archive or report that could not be read/parsed. */
    @ExceptionHandler({UncheckedIOException.class, com.fasterxml.jackson.core.JacksonException.class})
    public ProblemDetail handleCorruptArchive(Exception e, HttpServletRequest request) {
        return problem(ApiErrorCode.ARCHIVE_CORRUPTED, ApiErrorCode.ARCHIVE_CORRUPTED.defaultDetail(),
                request, e);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        return problem(ApiErrorCode.CALCULATION_FAILED, ApiErrorCode.CALCULATION_FAILED.defaultDetail(),
                request, e);
    }

    private ProblemDetail problem(
            ApiErrorCode code, String detail, HttpServletRequest request, Exception serverCause) {
        // For a server error, discard the caller-supplied detail entirely and log
        // the real cause; the body carries only the code's fixed safe message.
        String safeDetail = code.isServerError() ? code.defaultDetail() : detail;
        if (serverCause != null) {
            log.error("{} for {} {}", code, request.getMethod(), request.getRequestURI(), serverCause);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), safeDetail);
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        Object correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId.toString());
        }
        problem.setProperty("timestamp", clock.instant());
        return problem;
    }

    private ApiErrorCode codeForParameter(String name, Class<?> requiredType) {
        if (name == null) {
            return ApiErrorCode.INVALID_DATE;
        }
        return switch (name) {
            case "date", "from", "to" -> ApiErrorCode.INVALID_DATE;
            case "month", "year" -> ApiErrorCode.INVALID_MONTH;
            case "precision" -> ApiErrorCode.INVALID_PRECISION;
            case "source" -> ApiErrorCode.INVALID_SOURCE;
            default -> requiredType == java.time.LocalDate.class
                    ? ApiErrorCode.INVALID_DATE
                    : ApiErrorCode.INVALID_DATE;
        };
    }
}
