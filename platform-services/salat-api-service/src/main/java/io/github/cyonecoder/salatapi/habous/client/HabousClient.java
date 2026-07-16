package io.github.cyonecoder.salatapi.habous.client;

import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the official Morocco (Habous) timetable endpoint.
 *
 * <p>Uses the JDK {@link HttpClient} with explicit connection and request
 * timeouts and a clear {@code User-Agent}. <strong>TLS/certificate validation is
 * never disabled</strong>, and no access control, rate limit or CAPTCHA is ever
 * bypassed — a 403 is accepted as the site's answer, not worked around.
 *
 * <p>Temporary failures are retried with exponential backoff and jitter, bounded
 * by {@link HabousProperties#maxAttempts()} (spec 14). Permanent failures fail
 * immediately.
 *
 * <p><strong>Naming, deliberately.</strong> The original
 * {@code fetchMonthlyHtml(String habousCityId, LocalDate date)} took a date,
 * null-checked it, and then <em>discarded</em> it — the URI it built carried only
 * {@code ville}. Inspection of the official form confirms no month parameter
 * exists, so this port exposes
 * {@link #fetchCurrentPublishedTimetable(String)}, which says exactly what the
 * source does. See spec 11 and {@code docs/HABOUS_SOURCE.md}.
 */
@Component
public class HabousClient {

    private static final Logger log = LoggerFactory.getLogger(HabousClient.class);
    private static final int HTTP_OK = 200;

    /**
     * Abstraction over "fetch the currently published timetable for a city", to
     * keep collection testable.
     */
    @FunctionalInterface
    public interface Fetcher {
        /**
         * @param habousCityId the city's official {@code ville} identifier
         * @return the raw HTML of the currently published timetable page
         * @throws HabousUnavailableException if the page cannot be retrieved
         */
        String fetchCurrentPublishedTimetable(String habousCityId);
    }

    private final HttpClient httpClient;
    private final HabousRequestFactory requestFactory;
    private final HabousRetryPolicy retryPolicy;
    private final Sleeper sleeper;
    private final HabousProperties properties;

    public HabousClient(
            HabousRequestFactory requestFactory,
            HabousRetryPolicy retryPolicy,
            Sleeper sleeper,
            HabousProperties properties) {
        this(HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                requestFactory, retryPolicy, sleeper, properties);
    }

    /**
     * Creates a client backed by a caller-supplied {@link HttpClient} — used by
     * tests, and by any deployment that must augment the trust store to reach the
     * live endpoint (which serves an incomplete certificate chain). Augmenting
     * trust is not the same as disabling it.
     */
    public HabousClient(
            HttpClient httpClient,
            HabousRequestFactory requestFactory,
            HabousRetryPolicy retryPolicy,
            Sleeper sleeper,
            HabousProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Fetches the raw HTML of the timetable the official site is publishing
     * <em>right now</em> for the given city (matches {@link Fetcher}).
     *
     * <p>The period is chosen by the source, not the caller: the response covers
     * the current Hijri month, which straddles two Gregorian months.
     *
     * @param habousCityId the city's official {@code ville} identifier
     * @return the raw HTML
     * @throws HabousUnavailableException if the page cannot be retrieved within
     *                                    the configured attempts
     */
    public String fetchCurrentPublishedTimetable(String habousCityId) {
        Objects.requireNonNull(habousCityId, "habousCityId");
        URI uri = requestFactory.currentPublishedTimetableUri(habousCityId);

        HabousUnavailableException lastFailure = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try {
                return send(uri, habousCityId);
            } catch (HabousUnavailableException e) {
                lastFailure = e;
                boolean lastAttempt = attempt == retryPolicy.maxAttempts();
                if (lastAttempt || !retryPolicy.isRetryable(e)) {
                    throw e;
                }
                Duration backoff = retryPolicy.backoffFor(attempt);
                log.warn("Habous attempt {}/{} failed for city {} ({}); retrying in {}ms",
                        attempt, retryPolicy.maxAttempts(), habousCityId, e.getMessage(),
                        backoff.toMillis());
                pause(backoff);
            }
        }
        throw Objects.requireNonNull(lastFailure);
    }

    private String send(URI uri, String habousCityId) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "text/html")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status != HTTP_OK) {
                throw new HabousUnavailableException(
                        "Habous returned HTTP " + status + " for city " + habousCityId, status);
            }
            return response.body();
        } catch (IOException e) {
            throw new HabousUnavailableException(
                    "Failed to reach Habous for city " + habousCityId, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HabousUnavailableException("Interrupted contacting Habous", null, e);
        }
    }

    private void pause(Duration duration) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HabousUnavailableException("Interrupted while backing off", null, e);
        }
    }
}
