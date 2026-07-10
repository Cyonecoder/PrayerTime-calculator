package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A validated official Habous timetable snapshot for one city, as bundled under
 * {@code /official/habous/}. Immutable and never modified after parsing: no
 * rounding and no corrections are ever applied to these values.
 *
 * @param cityId         application city slug
 * @param habousCityId   the Habous numeric city id
 * @param sourceChecksum the SHA-256 of the official source document
 * @param parserVersion  the parser version that produced the rows
 * @param fetchedAt      when the source was fetched
 * @param days           the per-date official times (keyed and ordered by date)
 */
public record OfficialSnapshot(
        String cityId,
        String habousCityId,
        String sourceChecksum,
        String parserVersion,
        Instant fetchedAt,
        Map<LocalDate, PrayerTimes> days) {

    public OfficialSnapshot {
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(sourceChecksum, "sourceChecksum");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        days = Map.copyOf(new TreeMap<>(Objects.requireNonNull(days, "days")));
    }

    /** @return the official times for {@code date}, if present. */
    public Optional<PrayerTimes> day(LocalDate date) {
        return Optional.ofNullable(days.get(date));
    }

    /** @return the covered dates, ascending. */
    public List<LocalDate> dates() {
        return days.keySet().stream().sorted().toList();
    }

    /** @return the official minute-of-day for one prayer on {@code date}, if present. */
    public Optional<Integer> minuteOfDay(LocalDate date, Prayer prayer) {
        return day(date).map(t -> minuteOf(timeFor(t, prayer)));
    }

    private static int minuteOf(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    static LocalTime timeFor(PrayerTimes t, Prayer prayer) {
        return switch (prayer) {
            case FAJR -> t.fajr();
            case SUNRISE -> t.sunrise();
            case DHUHR -> t.dhuhr();
            case ASR -> t.asr();
            case MAGHRIB -> t.maghrib();
            case ISHA -> t.isha();
        };
    }
}
