package io.github.cyonecoder.salatapi.api.dto.request;

import io.github.cyonecoder.salatapi.api.exception.ApiErrorCode;
import io.github.cyonecoder.salatapi.api.exception.ApiException;
import java.util.Locale;

/** The precision of a returned sec-approach time (spec 23.10). */
public enum Precision {
    /** Floored to the whole minute — the published sec-approach time. */
    MINUTE,
    /** To the second — {@code rawSeconds + offset} before the floor. */
    SECOND;

    /**
     * @param value {@code minute} or {@code second} (any case), or blank for the default
     * @return the parsed precision, defaulting to {@link #MINUTE}
     */
    public static Precision parse(String value) {
        if (value == null || value.isBlank()) {
            return MINUTE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "minute" -> MINUTE;
            case "second" -> SECOND;
            default -> throw new ApiException(
                    ApiErrorCode.INVALID_PRECISION, ApiErrorCode.INVALID_PRECISION.defaultDetail());
        };
    }

    /** @return the lowercase wire form */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
