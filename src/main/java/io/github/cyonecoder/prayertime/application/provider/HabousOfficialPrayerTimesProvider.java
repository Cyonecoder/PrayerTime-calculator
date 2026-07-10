package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Serves the exact, unmodified official Habous timetable value for a city/date
 * ({@link ProvenanceSource#HABOUS_OFFICIAL}).
 *
 * <p>Official values are served verbatim — never rounded, never corrected, never
 * recomputed. When no valid official value exists this provider throws
 * {@link HabousDataUnavailableException} ({@code 503}); it never silently falls
 * back to calculation. Callers that want a fallback must use
 * {@link PrayerSourceMode#AUTO} via {@link HabousCompatiblePrayerTimesProvider}.
 */
public final class HabousOfficialPrayerTimesProvider implements PrayerTimesProvider {

    private final OfficialHabousStore store;

    public HabousOfficialPrayerTimesProvider() {
        this(new OfficialHabousStore());
    }

    public HabousOfficialPrayerTimesProvider(OfficialHabousStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public ProvidedTimes get(String cityId, LocalDate date, PrayerSourceMode sourceMode) {
        return official(cityId, date, sourceMode)
                .orElseThrow(() -> new HabousDataUnavailableException(cityId, date,
                        store.snapshot(cityId).isEmpty()
                                ? "no validated snapshot for city"
                                : "date not covered by snapshot"));
    }

    /**
     * Looks up the official result without throwing.
     *
     * @param cityId     the application city slug
     * @param date       the date
     * @param sourceMode the mode to record on the result
     * @return the official result, or empty if unavailable
     */
    public Optional<ProvidedTimes> official(String cityId, LocalDate date, PrayerSourceMode sourceMode) {
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(date, "date");
        return store.snapshot(cityId).flatMap(snapshot -> snapshot.day(date).map(times ->
                new ProvidedTimes(snapshot.cityId(), date, sourceMode,
                        ProvenanceSource.HABOUS_OFFICIAL, true, false,
                        snapshot.habousCityId(), snapshot.sourceChecksum(), snapshot.fetchedAt(),
                        null, times, null)));
    }

    /** @return the raw official times for a city/date, if available. */
    public Optional<PrayerTimes> officialTimes(String cityId, LocalDate date) {
        return store.snapshot(cityId).flatMap(s -> s.day(date));
    }

    /** @return the backing store (for coverage and provenance queries). */
    public OfficialHabousStore store() {
        return store;
    }
}
