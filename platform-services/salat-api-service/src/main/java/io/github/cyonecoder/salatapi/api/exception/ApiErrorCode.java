package io.github.cyonecoder.salatapi.api.exception;

import org.springframework.http.HttpStatus;

/**
 * The stable error codes returned in every {@code ProblemDetail} (spec 24).
 *
 * <p>Each code carries its HTTP status and a <strong>safe, generic</strong>
 * default message. No message here — nor any message an {@link ApiException}
 * carries — may contain a stack trace, filesystem path, internal package name,
 * raw HTML, or environment value. The 500-level messages are deliberately fixed
 * so a server-side failure can never leak its cause.
 */
public enum ApiErrorCode {

    INVALID_DATE(HttpStatus.BAD_REQUEST, "The date must be an ISO date (YYYY-MM-DD)."),
    INVALID_MONTH(HttpStatus.BAD_REQUEST, "The month must be between 1 and 12 with a valid year."),
    INVALID_PRECISION(HttpStatus.BAD_REQUEST, "The precision must be 'minute' or 'second'."),
    INVALID_SOURCE(HttpStatus.BAD_REQUEST, "The source must be 'official', 'sec-approach' or 'best'."),
    DATE_RANGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "The requested date range is too large."),

    HABOUS_CITY_NOT_FOUND(HttpStatus.NOT_FOUND, "No such official Habous city."),
    APPLICATION_CITY_NOT_MAPPED(HttpStatus.NOT_FOUND, "No verified Habous mapping for this application city."),
    OFFICIAL_DATA_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "Official data is not available for the requested date."),
    OFFICIAL_MONTH_NOT_AVAILABLE(HttpStatus.NOT_FOUND, "No official archive exists for the requested month."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),

    SEC_APPROACH_CITY_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY,
            "This city is not one of the supported sec-approach cities."),

    ARCHIVE_CORRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "The stored archive could not be read."),
    CALCULATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");

    private final HttpStatus status;
    private final String defaultDetail;

    ApiErrorCode(HttpStatus status, String defaultDetail) {
        this.status = status;
        this.defaultDetail = defaultDetail;
    }

    /** @return the HTTP status this code maps to */
    public HttpStatus status() {
        return status;
    }

    /** @return a safe default message that never leaks internal detail */
    public String defaultDetail() {
        return defaultDetail;
    }

    /** @return whether this is a server-side (5xx) failure, whose cause must never be exposed */
    public boolean isServerError() {
        return status.is5xxServerError();
    }
}
