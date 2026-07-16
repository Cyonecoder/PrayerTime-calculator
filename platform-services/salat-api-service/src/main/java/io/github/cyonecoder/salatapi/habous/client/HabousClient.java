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
 * by {@link HabousProperties#maximumAttempts()} (spec 14). Permanent failures fail
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

    /**
     * @param httpClient the HTTP client to use. In production Spring injects the
     *                   trust-augmented {@code habousHttpClient} bean (see
     *                   {@code HabousTlsConfiguration}); tests pass a caller-supplied
     *                   client directly. Either way trust is augmented, never
     *                   disabled — the {@code @Qualifier} disambiguates which bean
     *                   Spring wires and is simply ignored on a direct call.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public HabousClient(
            @org.springframework.beans.factory.annotation.Qualifier("habousHttpClient")
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
        for (int attempt = 1; attempt <= retryPolicy.maximumAttempts(); attempt++) {
            try {
                return send(uri, habousCityId);
            } catch (HabousUnavailableException e) {
                lastFailure = e;
                boolean lastAttempt = attempt == retryPolicy.maximumAttempts();
                if (lastAttempt || !retryPolicy.isRetryable(e)) {
                    throw e;
                }
                Duration backoff = retryPolicy.backoffFor(attempt);
                log.warn("Habous attempt {}/{} failed for city {} ({}); retrying in {}ms",
                        attempt, retryPolicy.maximumAttempts(), habousCityId, e.getMessage(),
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
            // Name the real cause (class + root message) so a TLS/DNS/timeout
            // failure is diagnosable, not hidden behind a generic "failed to reach".
            Throwable root = rootCause(e);
            String rootDetail = root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : ": " + root.getMessage());
            throw new HabousUnavailableException(
                    "Failed to reach Habous for city " + habousCityId
                            + " via " + uri.getHost() + uri.getPath() + ": " + rootDetail,
                    null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HabousUnavailableException(
                    "Interrupted contacting Habous for city " + habousCityId, null, e);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
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
