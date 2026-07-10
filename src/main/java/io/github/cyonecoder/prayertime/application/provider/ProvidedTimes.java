package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A served prayer-time result plus full provenance metadata, mirroring the
 * public API contract.
 *
 * <p>Fields that do not apply to a given source are {@code null}: an official
 * result has no {@code parameterVersion}; a calculated result has no
 * {@code habousCityId}/{@code sourceChecksum}/{@code fetchedAt}. The optional
 * {@link #comparison()} is present only for {@link PrayerSourceMode#COMPARE}.
 *
 * @param cityId           application city slug
 * @param date             the requested civil date
 * @param sourceMode       the mode the caller asked for
 * @param source           the concrete source actually produced
 * @param official         whether the served values are the official authority
 * @param fallbackUsed     whether a fallback away from official was used
 * @param habousCityId     the Habous numeric city id (official/auto), else {@code null}
 * @param sourceChecksum   the official source checksum, else {@code null}
 * @param fetchedAt        when the official snapshot was fetched, else {@code null}
 * @param parameterVersion the reconstructed parameter version (calculated), else {@code null}
 * @param times            the served times
 * @param comparison       the official-vs-calculated comparison (COMPARE only), else {@code null}
 */
public record ProvidedTimes(
        String cityId,
        LocalDate date,
        PrayerSourceMode sourceMode,
        ProvenanceSource source,
        boolean official,
        boolean fallbackUsed,
        String habousCityId,
        String sourceChecksum,
        Instant fetchedAt,
        String parameterVersion,
        PrayerTimes times,
        Comparison comparison) {

    public ProvidedTimes {
        Objects.requireNonNull(cityId, "cityId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(sourceMode, "sourceMode");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(times, "times");
    }

    /** @return the comparison payload, if this is a COMPARE result. */
    public Optional<Comparison> comparisonOpt() {
        return Optional.ofNullable(comparison);
    }

    /**
     * Both results and their per-prayer signed differences (official − calculated,
     * in minutes) for {@link PrayerSourceMode#COMPARE}.
     *
     * @param official          the official times ({@code null} if unavailable)
     * @param calculated        the reconstructed times
     * @param differenceMinutes official−calculated minutes per prayer (empty when
     *                          official is unavailable)
     * @param officialAvailable whether an official value existed to compare
     */
    public record Comparison(
            PrayerTimes official,
            PrayerTimes calculated,
            Map<Prayer, Integer> differenceMinutes,
            boolean officialAvailable) {

        public Comparison {
            Objects.requireNonNull(calculated, "calculated");
            differenceMinutes = differenceMinutes == null
                    ? Map.of() : Map.copyOf(differenceMinutes);
        }

        /** @return prayers whose official and calculated minute differ. */
        public List<Prayer> mismatchedPrayers() {
            return differenceMinutes.entrySet().stream()
                    .filter(e -> e.getValue() != 0)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /** @return the largest absolute per-prayer difference in minutes (0 if none). */
        public int maxAbsoluteDifferenceMinutes() {
            return differenceMinutes.values().stream()
                    .mapToInt(Math::abs).max().orElse(0);
        }
    }
}
