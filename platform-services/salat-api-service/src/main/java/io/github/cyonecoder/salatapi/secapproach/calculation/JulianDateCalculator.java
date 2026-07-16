package io.github.cyonecoder.salatapi.secapproach.calculation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Converts a civil date and time to a Julian Date (JD) — the time scale the
 * solar-position formulae operate on.
 *
 * <p>Uses the standard Gregorian-calendar algorithm (Meeus,
 * <em>Astronomical Algorithms</em>, ch. 7):
 *
 * <pre>
 *   if month &le; 2:  year -= 1;  month += 12      // Jan/Feb belong to the previous year
 *   A = floor(year / 100)
 *   B = 2 - A + floor(A / 4)                        // Gregorian calendar correction
 *   JD = floor(365.25 * (year + 4716))
 *      + floor(30.6001 * (month + 1))
 *      + day + B - 1524.5
 * </pre>
 *
 * <p>The integer part yields the JD at 00:00 of the given day. The fractional day
 * from {@code referenceTime} is added, and the supplied UTC offset is subtracted
 * exactly once to reduce local civil time to Universal Time.
 *
 * <p>Pure: never reads the system clock or time zone. Ported verbatim from
 * {@code domain.calculation.JulianDateCalculator}; see {@code docs/EXTRACTION_MAP.md}.
 */
public final class JulianDateCalculator {

    private static final int LAST_MONTH_OF_PREVIOUS_YEAR = 2;
    private static final int MONTHS_PER_YEAR = 12;

    private static final double DAYS_PER_JULIAN_YEAR = 365.25;
    private static final double MONTH_TO_DAYS_FACTOR = 30.6001;
    private static final int JULIAN_YEAR_OFFSET = 4716;
    private static final double JULIAN_DAY_ANCHOR = 1524.5;
    private static final int GREGORIAN_CENTURY = 100;
    private static final int GREGORIAN_LEAP_CENTURY_DIVISOR = 4;
    private static final int GREGORIAN_CORRECTION_BASE = 2;

    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final double NANOS_PER_DAY = 86_400_000_000_000.0;

    /**
     * @param date          the civil calendar date (Gregorian)
     * @param referenceTime the civil time of day within {@code date}
     * @param offset        the UTC offset {@code date}/{@code referenceTime} are
     *                      expressed in; subtracted once to obtain UT
     * @return the Julian Date, including the fractional day
     */
    public double calculate(LocalDate date, LocalTime referenceTime, ZoneOffset offset) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(referenceTime, "referenceTime");
        Objects.requireNonNull(offset, "offset");

        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();

        // January and February are counted as months 13 and 14 of the prior year.
        if (month <= LAST_MONTH_OF_PREVIOUS_YEAR) {
            year -= 1;
            month += MONTHS_PER_YEAR;
        }

        int a = Math.floorDiv(year, GREGORIAN_CENTURY);
        int b = GREGORIAN_CORRECTION_BASE - a + Math.floorDiv(a, GREGORIAN_LEAP_CENTURY_DIVISOR);

        double julianDayAtMidnight =
                Math.floor(DAYS_PER_JULIAN_YEAR * (year + JULIAN_YEAR_OFFSET))
                        + Math.floor(MONTH_TO_DAYS_FACTOR * (month + 1))
                        + day + b - JULIAN_DAY_ANCHOR;

        double dayFraction = referenceTime.toNanoOfDay() / NANOS_PER_DAY;
        double offsetFraction = offset.getTotalSeconds() / SECONDS_PER_DAY;

        return julianDayAtMidnight + dayFraction - offsetFraction;
    }
}
