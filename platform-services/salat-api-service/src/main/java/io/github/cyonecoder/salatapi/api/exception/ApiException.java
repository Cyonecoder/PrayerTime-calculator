package io.github.cyonecoder.salatapi.api.exception;

/**
 * An application error with a stable {@link ApiErrorCode} and a caller-safe
 * message (spec 24).
 *
 * <p>The message on this exception is shown to the client, so callers must only
 * ever construct it with safe text — no paths, HTML, package names or environment
 * values. For server-side codes the handler ignores this message entirely and
 * uses the code's fixed default, so a 500 can never leak its cause.
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;

    public ApiException(ApiErrorCode code, String safeDetail) {
        super(safeDetail);
        this.code = code;
    }

    /** @return the stable error code */
    public ApiErrorCode code() {
        return code;
    }
}
