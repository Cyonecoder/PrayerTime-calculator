package io.github.cyonecoder.prayertime.infrastructure.habous;

import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimesResult;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousDay;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides official Morocco (Habous) prayer times for a city and date.
 *
 * <p>Isolated adapter: it fetches and caches the monthly timetable, then reads
 * the requested day out of it. It <strong>never</strong> falls back to
 * astronomical calculation — when Habous data is unavailable (no Habous ID for
 * the city, the endpoint is down/5xx, the HTML is unparseable, or the requested
 * day is simply absent) it returns {@link Optional#empty()} and lets the
 * <em>caller</em> decide what to do. The source label is
 * {@code "habous-official"}.
 */
public final class HabousPrayerTimesProvider {

    /** The source label recorded on every result produced here. */
    public static final String SOURCE = "habous-official";
    private static final String METHOD_NAME = "Habous Official";
    private static final ZoneId MOROCCO_ZONE = ZoneId.of("Africa/Casablanca");

    private final Fetcher fetcher;
    private final HabousHtmlParser parser;
    private final HabousCache cache;
    private final Clock clock;

    public HabousPrayerTimesProvider(Fetcher fetcher, HabousHtmlParser parser, HabousCache cache) {
        this(fetcher, parser, cache, Clock.systemUTC());
    }

    HabousPrayerTimesProvider(Fetcher fetcher, HabousHtmlParser parser, HabousCache cache, Clock clock) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the source label produced by this provider. */
    public String source() {
        return SOURCE;
    }

    /**
     * Looks up the official Habous times for the given city and date.
     *
     * @param city the city (must carry a Habous ID to be queryable)
     * @param date the requested date
     * @return the official times, or empty if unavailable (never guessed, never
     *         substituted with astronomical data)
     * @throws NullPointerException if any argument is {@code null}
     */
    public Optional<PrayerTimesResult> find(City city, LocalDate date) {
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(date, "date");

        Optional<String> habousCityId = city.habousCityId();
        if (habousCityId.isEmpty()) {
            return Optional.empty();
        }

        YearMonth month = YearMonth.from(date);
        Optional<HabousCache.Entry> cached = cache.get(habousCityId.get(), month);

        HabousCache.Entry entry;
        if (cached.isPresent()) {
            entry = cached.get();
        } else {
            Optional<HabousCache.Entry> fetched = fetchAndCache(habousCityId.get(), month, date);
            if (fetched.isEmpty()) {
                return Optional.empty();
            }
            entry = fetched.get();
        }

        return dayFor(entry.days(), date)
                .map(day -> toResult(day, date, entry.fetchedAt()));
    }

    private Optional<HabousCache.Entry> fetchAndCache(String cityId, YearMonth month, LocalDate date) {
        try {
            String html = fetcher.fetchMonthlyHtml(cityId, date);
            List<HabousDay> days = parser.parse(html);
            HabousCache.Entry entry = new HabousCache.Entry(
                    cityId, month, date, clock.instant(), SOURCE, days);
            cache.put(entry);
            return Optional.of(entry);
        } catch (HabousUnavailableException | HabousParseException e) {
            // Handled gracefully: do not crash, do not substitute other data.
            return Optional.empty();
        }
    }

    private Optional<HabousDay> dayFor(List<HabousDay> days, LocalDate date) {
        int dayOfMonth = date.getDayOfMonth();
        // Do not guess: only an exact day match is accepted.
        return days.stream().filter(d -> d.gregorianDay() == dayOfMonth).findFirst();
    }

    private PrayerTimesResult toResult(HabousDay day, LocalDate date, Instant fetchedAt) {
        PrayerTimes times = new PrayerTimes(
                date, day.fajr(), day.sunrise(), day.dhuhr(), day.asr(), day.maghrib(), day.isha());
        ZoneOffset offset = MOROCCO_ZONE.getRules().getOffset(date.atTime(12, 0));
        return new PrayerTimesResult(times, SOURCE, METHOD_NAME, fetchedAt, MOROCCO_ZONE, offset);
    }
}
