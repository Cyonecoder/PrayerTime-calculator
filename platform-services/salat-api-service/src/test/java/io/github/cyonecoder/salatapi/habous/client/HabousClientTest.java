package io.github.cyonecoder.salatapi.habous.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Habous client's real request and retry behaviour (spec 11 and 14), driven
 * against a local MockWebServer so no test touches the government site.
 */
class HabousClientTest {

    private MockWebServer server;
    private HabousClient client;
    private HabousProperties properties;
    private List<Duration> slept;

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new HabousProperties(
                server.url("/prieres/horaire_hijri_2.php").toString(),
                server.url("/prieres/index.php").toString(),
                "PrayerTime-Calculator/0.1 (+test)",
                1,
                Duration.ofMillis(500),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30));
        slept = new ArrayList<>();
        // Record backoffs instead of waiting: the retry policy's timing is tested
        // separately, and a real sleep would make this suite take ~7s for nothing.
        Sleeper recording = slept::add;
        client = new HabousClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new HabousRequestFactory(properties),
                new HabousRetryPolicy(properties),
                recording,
                properties);
    }

    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    void theRequestCarriesOnlyTheVilleParameter() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("<html>timetable</html>"));

        client.fetchCurrentPublishedTimetable("117");

        RecordedRequest request = server.takeRequest();
        // The official form exposes no month/year parameter, so the request must
        // not invent one. Only 'ville' may appear.
        assertThat(request.getRequestUrl().queryParameterNames()).containsExactly("ville");
        assertThat(request.getRequestUrl().queryParameter("ville")).isEqualTo("117");
        assertThat(request.getMethod()).isEqualTo("GET");
    }

    @Test
    void theExplicitUserAgentIsSent() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("<html>ok</html>"));

        client.fetchCurrentPublishedTimetable("1");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("User-Agent")).isEqualTo("PrayerTime-Calculator/0.1 (+test)");
        assertThat(request.getHeader("Accept")).isEqualTo("text/html");
    }

    @Test
    void theRawHtmlIsReturnedUnmodified() {
        String html = "<html><table id=\"horaire\">محرّم 1448</table></html>";
        server.enqueue(new MockResponse().setBody(html));

        assertThat(client.fetchCurrentPublishedTimetable("1")).isEqualTo(html);
    }

    @Test
    void aTemporaryFailureIsRetriedAndCanSucceed() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
        server.enqueue(new MockResponse().setBody("<html>recovered</html>"));

        assertThat(client.fetchCurrentPublishedTimetable("1")).isEqualTo("<html>recovered</html>");
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(slept).hasSize(2); // backed off between attempts
    }

    @Test
    void retriesAreBoundedByMaxAttempts() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503)); // never reached

        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable("1"))
                .isInstanceOf(HabousUnavailableException.class)
                .hasMessageContaining("HTTP 503");

        // 3 attempts total, not 3 retries on top of the first.
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable("999999"))
                .isInstanceOf(HabousUnavailableException.class)
                .hasMessageContaining("HTTP 404");

        // Retrying a 404 would just add load without changing the answer.
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void aForbiddenResponseIsAcceptedRatherThanWorkedAround() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));

        // Access controls are the site's answer; the client must not retry around
        // them, rotate identity, or otherwise attempt a bypass.
        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable("1"))
                .isInstanceOf(HabousUnavailableException.class)
                .hasMessageContaining("HTTP 403");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void aNonSuccessStatusCarriesItsCodeForTheRetryPolicy() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("maintenance"));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable("1"))
                .isInstanceOfSatisfying(HabousUnavailableException.class, e ->
                        assertThat(e.statusCode()).contains(503));
    }

    @Test
    void anUnreachableEndpointIsReportedRatherThanHanging() throws IOException {
        server.shutdown();

        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable("1"))
                .isInstanceOf(HabousUnavailableException.class)
                .hasMessageContaining("Failed to reach Habous");
    }

    @Test
    void theRequestFactoryOffersNoWayToAskForAHistoricalMonth() {
        HabousRequestFactory factory = new HabousRequestFactory(properties);

        // The only public request-building method takes a city and nothing else.
        // If a month parameter is ever proven to exist, this test should be the
        // thing that changes first.
        assertThat(factory.currentPublishedTimetableUri("42").getQuery()).isEqualTo("ville=42");
        assertThat(HabousRequestFactory.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("currentPublishedTimetableUri", "villeDirectoryUri");
    }

    @Test
    void aBlankCityIdIsRejectedBeforeAnyRequestIsMade() {
        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }
}
