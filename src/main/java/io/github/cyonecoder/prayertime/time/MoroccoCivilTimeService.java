package io.github.cyonecoder.prayertime.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves Morocco's civil-time offset and clock mode from the IANA time-zone
 * database ({@code Africa/Casablanca}).
 *
 * <p>Design notes:
 * <ul>
 *   <li>The IANA/Java time-zone data is the single source of truth. No legal
 *       transition dates (e.g. the Ramadan start/end) are stored in code, so
 *       the module keeps working as future tzdata updates land.</li>
 *   <li>Offsets are resolved <em>at local noon</em>, which is never inside a
 *       clock-transition gap or overlap, removing any ambiguity.</li>
 *   <li>The public clock label is derived from the <em>total</em> UTC offset,
 *       not from {@link ZoneRules#isDaylightSavings}. Morocco's data models the
 *       Ramadan suspension with negative-DST semantics, so the DST flag would
 *       be misleading.</li>
 *   <li>The JVM default time zone is never consulted; the zone is always
 *       explicit.</li>
 * </ul>
 */
public final class MoroccoCivilTimeService {

    private static final ZoneId MOROCCO = ZoneId.of("Africa/Casablanca");
    private static final ZoneOffset STANDARD_SUMMER_OFFSET = ZoneOffset.ofHours(1);
    private static final ZoneOffset RAMADAN_SUSPENSION_OFFSET = ZoneOffset.ofHours(0);

    /**
     * Raised when the resolved offset is not one of Morocco's expected modern
     * offsets (+01:00 or +00:00), rather than silently guessing a mode.
     */
    public static final class UnsupportedOffsetException extends RuntimeException {
        private final transient ZoneOffset offset;

        UnsupportedOffsetException(LocalDate date, ZoneOffset offset) {
            super("Unexpected Morocco civil offset " + offset + " on " + date
                    + "; only +01:00 and +00:00 are supported");
            this.offset = offset;
        }

        /** @return the offset that could not be classified */
        public ZoneOffset offset() {
            return offset;
        }
    }

    /** @return the fixed Morocco zone, {@code Africa/Casablanca}. */
    public ZoneId zoneId() {
        return MOROCCO;
    }

    /**
     * Classifies a total UTC offset into a {@link MoroccoClockMode}. Pure and
     * total: unknown offsets map to {@link MoroccoClockMode#OTHER}.
     *
     * @param offset the total UTC offset
     * @return the corresponding clock mode
     */
    public MoroccoClockMode modeFor(ZoneOffset offset) {
        Objects.requireNonNull(offset, "offset");
        if (offset.equals(STANDARD_SUMMER_OFFSET)) {
            return MoroccoClockMode.UTC_PLUS_ONE;
        }
        if (offset.equals(RAMADAN_SUSPENSION_OFFSET)) {
            return MoroccoClockMode.UTC_ZERO_RAMADAN_SUSPENSION;
        }
        return MoroccoClockMode.OTHER;
    }

    /**
     * Resolves the total UTC offset for the given date, evaluated at local
     * noon to avoid transition-hour ambiguity.
     *
     * @param date the civil date
     * @return the total UTC offset in effect at local noon on {@code date}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public ZoneOffset offsetFor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return MOROCCO.getRules().getOffset(date.atTime(LocalTime.NOON));
    }

    /**
     * The local-noon {@link ZonedDateTime} for the given date in the Morocco
     * zone.
     *
     * @param date the civil date
     * @return {@code date} at 12:00 local, zoned to {@code Africa/Casablanca}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    public ZonedDateTime localNoon(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return ZonedDateTime.of(date, LocalTime.NOON, MOROCCO);
    }

    /**
     * Builds a full clock snapshot for the given date.
     *
     * @param date the civil date
     * @return the {@link MoroccoClockInfo} for that day
     * @throws NullPointerException     if {@code date} is {@code null}
     * @throws UnsupportedOffsetException if the resolved offset is neither
     *                                    +01:00 nor +00:00
     */
    public MoroccoClockInfo clockInfoFor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        ZoneOffset offset = offsetFor(date);
        MoroccoClockMode mode = modeFor(offset);
        if (mode == MoroccoClockMode.OTHER) {
            throw new UnsupportedOffsetException(date, offset);
        }

        ZoneRules rules = MOROCCO.getRules();
        Instant noon = localNoon(date).toInstant();
        Optional<Instant> next =
                Optional.ofNullable(rules.nextTransition(noon)).map(ZoneOffsetTransition::getInstant);
        Optional<Instant> previous =
                Optional.ofNullable(rules.previousTransition(noon)).map(ZoneOffsetTransition::getInstant);

        return new MoroccoClockInfo(date, MOROCCO, offset, mode, next, previous);
    }

    /**
     * Lists all offset transitions whose instant falls within the given
     * calendar year (UTC), in chronological order.
     *
     * @param year the calendar year
     * @return the transitions occurring during {@code year} (possibly empty)
     */
    public List<ZoneOffsetTransition> transitionsForYear(int year) {
        ZoneRules rules = MOROCCO.getRules();
        Instant start = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endExclusive = LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<ZoneOffsetTransition> result = new ArrayList<>();
        // Start scanning just before the year so a transition exactly at
        // midnight on 1 January is not skipped.
        ZoneOffsetTransition transition = rules.nextTransition(start.minusNanos(1));
        while (transition != null && transition.getInstant().isBefore(endExclusive)) {
            if (!transition.getInstant().isBefore(start)) {
                result.add(transition);
            }
            transition = rules.nextTransition(transition.getInstant());
        }
        return result;
    }
}
