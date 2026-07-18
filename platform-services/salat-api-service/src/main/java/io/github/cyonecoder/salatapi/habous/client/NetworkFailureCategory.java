package io.github.cyonecoder.salatapi.habous.client;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;

/**
 * A coarse classification of why a Habous fetch failed, used for diagnostics and
 * for the aggregated per-run failure summary (so 191 cities failing for one TLS
 * reason produces one grouped line, not 191 stack traces).
 */
public enum NetworkFailureCategory {

    /** The TLS handshake failed — e.g. {@code PKIX path building failed}. */
    TLS_HANDSHAKE_FAILED,
    /** The host name could not be resolved. */
    DNS_FAILED,
    /** The connection could not be established in time (or was refused). */
    CONNECT_TIMEOUT,
    /** The request was sent but no response arrived in time. */
    REQUEST_TIMEOUT,
    /** The site returned 403 Forbidden — an access control, never worked around. */
    HTTP_403,
    /** The site returned 429 Too Many Requests. */
    HTTP_429,
    /** The site returned a 5xx server error. */
    HTTP_5XX,
    /** The fetch thread was interrupted. */
    INTERRUPTED,
    /** Any other network or protocol error. */
    UNKNOWN_NETWORK_ERROR;

    /**
     * Classifies a failure from its HTTP status (if any) and its transport cause.
     *
     * @param statusCode the HTTP status, or null for a transport-level failure
     * @param cause      the underlying throwable, or null
     * @return the category
     */
    public static NetworkFailureCategory classify(Integer statusCode, Throwable cause) {
        if (statusCode != null) {
            if (statusCode == 403) {
                return HTTP_403;
            }
            if (statusCode == 429) {
                return HTTP_429;
            }
            if (statusCode >= 500 && statusCode < 600) {
                return HTTP_5XX;
            }
            return UNKNOWN_NETWORK_ERROR;
        }
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SSLException) {
                return TLS_HANDSHAKE_FAILED;
            }
            if (t instanceof UnknownHostException) {
                return DNS_FAILED;
            }
            // HttpConnectTimeoutException extends HttpTimeoutException, so test it first.
            if (t instanceof HttpConnectTimeoutException || t instanceof ConnectException) {
                return CONNECT_TIMEOUT;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return REQUEST_TIMEOUT;
            }
            if (t instanceof InterruptedException) {
                return INTERRUPTED;
            }
        }
        return UNKNOWN_NETWORK_ERROR;
    }
}
