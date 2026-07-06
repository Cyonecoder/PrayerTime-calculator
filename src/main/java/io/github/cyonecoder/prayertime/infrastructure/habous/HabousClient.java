package io.github.cyonecoder.prayertime.infrastructure.habous;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;

/**
 * HTTP client for the official Morocco (Habous) timetable endpoint.
 *
 * <p>Uses the JDK {@link HttpClient} with explicit connection and request
 * timeouts and a clear {@code User-Agent}. TLS/certificate validation is never
 * disabled. Non-success and 5xx responses raise
 * {@link HabousUnavailableException} so callers can handle an outage without
 * the whole application crashing.
 *
 * <p>Its {@link #fetchMonthlyHtml} method matches the {@link Fetcher} SAM, so a
 * {@code new HabousClient()::fetchMonthlyHtml} method reference can be handed to
 * a provider (and a fixture-backed lambda substituted in tests).
 */
public final class HabousClient {

    private static final URI BASE_URI =
            URI.create("https://www.habous.gov.ma/prieres/horaire_hijri_2.php");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT =
            "PrayerTime-Calculator/0.1 (+https://github.com/Cyonecoder/PrayerTime-calculator)";
    private static final int HTTP_OK = 200;

    /** Abstraction over "fetch the monthly HTML for a city", to keep providers testable. */
    @FunctionalInterface
    public interface Fetcher {
        /**
         * @param habousCityId the city's Habous identifier
         * @param date         a date within the desired month
         * @return the raw HTML of the monthly timetable page
         * @throws HabousUnavailableException if the page cannot be retrieved
         */
        String fetchMonthlyHtml(String habousCityId, LocalDate date);
    }

    /** Raised when the Habous endpoint is unreachable or returns a non-200 status. */
    public static final class HabousUnavailableException extends RuntimeException {
        public HabousUnavailableException(String message) {
            super(message);
        }

        public HabousUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient httpClient;

    public HabousClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HabousClient(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Fetches the raw monthly timetable HTML for a city (matches {@link Fetcher}).
     *
     * @param habousCityId the city's Habous identifier
     * @param date         a date within the desired month
     * @return the raw HTML
     * @throws HabousUnavailableException if the page cannot be retrieved
     */
    public String fetchMonthlyHtml(String habousCityId, LocalDate date) {
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(date, "date");

        HttpRequest request = HttpRequest.newBuilder(buildUri(habousCityId))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .GET()
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status != HTTP_OK) {
                throw new HabousUnavailableException(
                        "Habous returned HTTP " + status + " for city " + habousCityId);
            }
            return response.body();
        } catch (IOException e) {
            throw new HabousUnavailableException("Failed to reach Habous for city " + habousCityId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HabousUnavailableException("Interrupted contacting Habous", e);
        }
    }

    private URI buildUri(String habousCityId) {
        String query = "ville=" + URLEncoder.encode(habousCityId, StandardCharsets.UTF_8);
        return URI.create(BASE_URI + "?" + query);
    }
}
