package io.github.cyonecoder.prayertime.infrastructure.habous;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimesResult;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HabousPrayerTimesProviderTest {

    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-01T09:00:00Z"), ZoneOffset.UTC);

    private final HabousHtmlParser parser = new HabousHtmlParser();
    private final HabousCache cache = new HabousCache();

    private static String fixtureHtml() throws IOException {
        try (InputStream in = HabousPrayerTimesProviderTest.class
                .getResourceAsStream("/fixtures/habous/horaire-casablanca-2026-07.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static City cityWithHabousId(String id) {
        return new City("casablanca", "Casablanca",
                new Coordinates(33.5731, -7.5898, 50), CASA_ZONE, Optional.of(id));
    }

    private HabousPrayerTimesProvider provider(Fetcher fetcher) {
        return new HabousPrayerTimesProvider(fetcher, parser, cache, FIXED_CLOCK);
    }

    @Test
    void returnsOfficialTimesForAPresentDate() throws IOException {
        String html = fixtureHtml();
        HabousPrayerTimesProvider provider = provider((id, date) -> html);

        Optional<PrayerTimesResult> result =
                provider.find(cityWithHabousId("101"), LocalDate.of(2026, 7, 15));

        assertThat(result).isPresent();
        PrayerTimesResult r = result.get();
        assertThat(r.source()).isEqualTo("habous-official");
        assertThat(r.methodName()).isEqualTo("Habous Official");
        assertThat(r.timezone()).isEqualTo(CASA_ZONE);
        assertThat(r.utcOffset()).isEqualTo(ZoneOffset.ofHours(1)); // July -> UTC+1
        assertThat(r.generatedAt()).isEqualTo(Instant.parse("2026-07-01T09:00:00Z"));
        assertThat(r.prayerTimes().fajr()).isEqualTo(LocalTime.of(5, 15));
        assertThat(r.prayerTimes().isha()).isEqualTo(LocalTime.of(20, 15));
    }

    @Test
    void cachesTheMonthlyResponseAndDoesNotRefetch() throws IOException {
        String html = fixtureHtml();
        AtomicInteger fetches = new AtomicInteger();
        HabousPrayerTimesProvider provider = provider((id, date) -> {
            fetches.incrementAndGet();
            return html;
        });
        City city = cityWithHabousId("101");

        provider.find(city, LocalDate.of(2026, 7, 10));
        provider.find(city, LocalDate.of(2026, 7, 20)); // same month

        assertThat(fetches.get()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void doesNotGuessWhenTheRequestedDayIsAbsent() throws IOException {
        String html = fixtureHtml(); // only days 1..30
        HabousPrayerTimesProvider provider = provider((id, date) -> html);

        assertThat(provider.find(cityWithHabousId("101"), LocalDate.of(2026, 7, 31))).isEmpty();
    }

    @Test
    void returnsEmptyWhenCityHasNoHabousId() {
        HabousPrayerTimesProvider provider = provider((id, date) -> {
            throw new AssertionError("must not fetch without a Habous id");
        });
        City noId = City.of("casablanca", "Casablanca",
                new Coordinates(33.5731, -7.5898, 50), CASA_ZONE);

        assertThat(provider.find(noId, LocalDate.of(2026, 7, 15))).isEmpty();
    }

    @Test
    void handles5xxOutageGracefullyWithoutFallback() {
        HabousPrayerTimesProvider provider = provider((id, date) -> {
            throw new HabousUnavailableException("Habous returned HTTP 503 for city " + id);
        });

        // Returns empty (no crash, no astronomical substitution).
        assertThat(provider.find(cityWithHabousId("101"), LocalDate.of(2026, 7, 15))).isEmpty();
    }

    @Test
    void handlesUnparseableHtmlGracefully() {
        HabousPrayerTimesProvider provider = provider((id, date) -> "<html>no table</html>");

        assertThat(provider.find(cityWithHabousId("101"), LocalDate.of(2026, 7, 15))).isEmpty();
    }

    @Test
    void exposesTheHabousSourceLabel() {
        assertThat(provider((id, date) -> "").source()).isEqualTo("habous-official");
    }
}
