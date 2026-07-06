package io.github.cyonecoder.prayertime.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * A snapshot of Morocco's civil-clock state for a single day, derived entirely
 * from the IANA time-zone rules.
 *
 * @param date               the civil date this snapshot describes
 * @param zoneId             the zone the offset was resolved in
 *                           ({@code Africa/Casablanca})
 * @param offset             the total UTC offset in effect at local noon
 * @param mode               the classified clock regime for {@code offset}
 * @param nextTransition     the next offset transition at or after this day, if
 *                           any; never {@code null}
 * @param previousTransition the most recent offset transition before this day,
 *                           if any; never {@code null}
 */
public record MoroccoClockInfo(
        LocalDate date,
        ZoneId zoneId,
        ZoneOffset offset,
        MoroccoClockMode mode,
        Optional<Instant> nextTransition,
        Optional<Instant> previousTransition) {

    /**
     * @throws NullPointerException if any component is {@code null}
     */
    public MoroccoClockInfo {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(nextTransition, "nextTransition (use Optional.empty())");
        Objects.requireNonNull(previousTransition, "previousTransition (use Optional.empty())");
    }
}
