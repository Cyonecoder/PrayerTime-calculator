package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The HTTP adapter routes the documented endpoints to the provider correctly. */
class PrayerTimesHttpServerTest {

    private PrayerTimesHttpServer server;
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = new PrayerTimesHttpServer();
        port = server.start(0);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void officialEndpointReturnsExactValues() throws Exception {
        HttpResponse<String> r = get(
                "/api/v1/cities/casablanca/prayer-times?date=2026-07-07&source=official");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .contains("\"source\": \"HABOUS_OFFICIAL\"")
                .contains("\"official\": true")
                .contains("\"fajr\": \"04:38\"")
                .contains("\"sunrise\": \"06:23\"");
    }

    @Test
    void autoEndpointDefaultsAndPrefersOfficial() throws Exception {
        HttpResponse<String> r = get("/api/v1/cities/casablanca/prayer-times?date=2026-07-07&source=auto");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"sourceMode\": \"AUTO\"").contains("\"official\": true");
    }

    @Test
    void officialEndpointReturns503WhenUnavailable() throws Exception {
        HttpResponse<String> r = get(
                "/api/v1/cities/casablanca/prayer-times?date=2026-01-01&source=official");
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains(HabousDataUnavailableException.CODE);
    }

    @Test
    void comparisonEndpointReturnsBothAndDifferences() throws Exception {
        HttpResponse<String> r = get("/api/v1/cities/casablanca/prayer-times/comparison?date=2026-07-07");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body())
                .contains("\"comparison\"")
                .contains("\"officialAvailable\": true")
                .contains("\"differenceMinutes\"");
    }

    @Test
    void unknownCityReturns400() throws Exception {
        HttpResponse<String> r = get("/api/v1/cities/atlantis/prayer-times?date=2026-07-07&source=calculated");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("UNKNOWN_CITY");
    }
}
