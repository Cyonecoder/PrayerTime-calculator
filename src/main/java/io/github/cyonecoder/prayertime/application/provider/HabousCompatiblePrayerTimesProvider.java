package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The top-level provider that honours every {@link PrayerSourceMode} by
 * dispatching to the official and calculated collaborators.
 *
 * <ul>
 *   <li>{@code CALCULATED} → reconstructed calculation, no official override.</li>
 *   <li>{@code OFFICIAL} → exact official value or {@code 503} (never calculated).</li>
 *   <li>{@code AUTO} → official when available, else a flagged calculated fallback.</li>
 *   <li>{@code COMPARE} → both results plus per-prayer differences.</li>
 * </ul>
 */
public final class HabousCompatiblePrayerTimesProvider implements PrayerTimesProvider {

    private final HabousOfficialPrayerTimesProvider official;
    private final AstronomicalPrayerTimesProvider calculated;

    public HabousCompatiblePrayerTimesProvider() {
        this(new HabousOfficialPrayerTimesProvider(), new AstronomicalPrayerTimesProvider());
    }

    public HabousCompatiblePrayerTimesProvider(
            HabousOfficialPrayerTimesProvider official, AstronomicalPrayerTimesProvider calculated) {
        this.official = Objects.requireNonNull(official, "official");
        this.calculated = Objects.requireNonNull(calculated, "calculated");
    }

    @Override
    public ProvidedTimes get(String cityId, LocalDate date, PrayerSourceMode sourceMode) {
        Objects.requireNonNull(sourceMode, "sourceMode");
        return switch (sourceMode) {
            case CALCULATED -> calculated.calculated(cityId, date, sourceMode, false);
            case OFFICIAL -> official.get(cityId, date, sourceMode);
            case AUTO -> auto(cityId, date);
            case COMPARE -> compare(cityId, date);
        };
    }

    private ProvidedTimes auto(String cityId, LocalDate date) {
        Optional<ProvidedTimes> off = official.official(cityId, date, PrayerSourceMode.AUTO);
        // Priority: validated official value first; otherwise a clearly-flagged
        // reconstructed-calculation fallback.
        return off.orElseGet(() -> calculated.calculated(cityId, date, PrayerSourceMode.AUTO, true));
    }

    private ProvidedTimes compare(String cityId, LocalDate date) {
        PrayerTimes calc = calculated.computeTimes(cityId, date);
        Optional<PrayerTimes> off = official.officialTimes(cityId, date);
        boolean available = off.isPresent();

        Map<Prayer, Integer> diffs = new EnumMap<>(Prayer.class);
        if (available) {
            for (Prayer p : Prayer.values()) {
                diffs.put(p, minuteOf(time(off.get(), p)) - minuteOf(time(calc, p)));
            }
        }
        ProvidedTimes.Comparison comparison =
                new ProvidedTimes.Comparison(off.orElse(null), calc, diffs, available);

        // The served `times`/`source` reflect the authoritative value when present.
        PrayerTimes served = off.orElse(calc);
        ProvenanceSource source = available
                ? ProvenanceSource.HABOUS_OFFICIAL : ProvenanceSource.ASTRONOMICAL_RECONSTRUCTED;
        Optional<ProvidedTimes> offMeta = official.official(cityId, date, PrayerSourceMode.COMPARE);
        return new ProvidedTimes(cityId, date, PrayerSourceMode.COMPARE, source,
                available, false,
                offMeta.map(ProvidedTimes::habousCityId).orElse(null),
                offMeta.map(ProvidedTimes::sourceChecksum).orElse(null),
                offMeta.map(ProvidedTimes::fetchedAt).orElse(null),
                available ? null : AstronomicalPrayerTimesProvider.PARAMETER_VERSION,
                served, comparison);
    }

    private static LocalTime time(PrayerTimes t, Prayer prayer) {
        return OfficialSnapshot.timeFor(t, prayer);
    }

    private static int minuteOf(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
