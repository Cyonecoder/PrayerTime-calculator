package io.github.cyonecoder.prayertime.infrastructure.habous;

import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousDay;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of successful monthly Habous responses.
 *
 * <p>Each entry records provenance: the fetched timestamp, the city ID, the
 * date that triggered the fetch, and the source label, alongside the parsed
 * days for the whole month.
 */
public final class HabousCache {

    /**
     * A cached month of Habous times with provenance metadata.
     *
     * @param cityId        the Habous city identifier
     * @param month         the cached month
     * @param requestedDate the date whose request populated this entry
     * @param fetchedAt     when the data was fetched
     * @param source        the source label (e.g. {@code "habous-official"})
     * @param days          the parsed days for the month
     */
    public record Entry(
            String cityId,
            YearMonth month,
            LocalDate requestedDate,
            Instant fetchedAt,
            String source,
            List<HabousDay> days) {

        public Entry {
            Objects.requireNonNull(cityId, "cityId");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(requestedDate, "requestedDate");
            Objects.requireNonNull(fetchedAt, "fetchedAt");
            Objects.requireNonNull(source, "source");
            days = List.copyOf(Objects.requireNonNull(days, "days"));
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * @return the cached entry for the city/month, if present
     */
    public Optional<Entry> get(String cityId, YearMonth month) {
        return Optional.ofNullable(entries.get(key(cityId, month)));
    }

    /** Stores (or replaces) the cached entry for its city/month. */
    public void put(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(key(entry.cityId(), entry.month()), entry);
    }

    /** @return the number of cached months (mainly for tests). */
    public int size() {
        return entries.size();
    }

    private static String key(String cityId, YearMonth month) {
        return Objects.requireNonNull(cityId, "cityId") + "@" + Objects.requireNonNull(month, "month");
    }
}
