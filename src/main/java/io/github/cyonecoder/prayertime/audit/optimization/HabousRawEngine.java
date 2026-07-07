package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.domain.calculation.HourAngleCalculator;
import io.github.cyonecoder.prayertime.domain.calculation.HourAngleCalculator.Direction;
import io.github.cyonecoder.prayertime.domain.calculation.JulianDateCalculator;
import io.github.cyonecoder.prayertime.domain.calculation.SolarPosition;
import io.github.cyonecoder.prayertime.domain.calculation.SolarPositionCalculator;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Computes the <em>raw</em> (un-adjusted, un-rounded) prayer event time in
 * seconds of the day for a candidate parameter set, then applies the candidate's
 * adjustment and rounding to obtain the published minute.
 *
 * <p>This reuses the production astronomical components verbatim
 * ({@link JulianDateCalculator}, {@link SolarPositionCalculator},
 * {@link HourAngleCalculator}); it duplicates none of the formulae and mutates
 * no production configuration. The search operates as if against an official
 * city table (observer elevation is treated as sea level, so the sunrise/Maghrib
 * altitude knob is used directly with no elevation dip).
 */
public final class HabousRawEngine {

    private static final double SECONDS_PER_HOUR = 3600.0;

    private final JulianDateCalculator julianDateCalculator;
    private final SolarPositionCalculator solarPositionCalculator;
    private final HourAngleCalculator hourAngleCalculator;

    public HabousRawEngine() {
        this(new JulianDateCalculator(), new SolarPositionCalculator(), new HourAngleCalculator());
    }

    public HabousRawEngine(
            JulianDateCalculator julianDateCalculator,
            SolarPositionCalculator solarPositionCalculator,
            HourAngleCalculator hourAngleCalculator) {
        this.julianDateCalculator = Objects.requireNonNull(julianDateCalculator);
        this.solarPositionCalculator = Objects.requireNonNull(solarPositionCalculator);
        this.hourAngleCalculator = Objects.requireNonNull(hourAngleCalculator);
    }

    /**
     * The raw event time for one prayer, in seconds of the day, before any
     * adjustment or rounding.
     *
     * @param prayer      the prayer to compute
     * @param candidate   the parameter set
     * @param coordinates the observer location
     * @param zone        the civil zone for the offset
     * @param date        the date
     * @return the raw seconds of day (may be fractional)
     */
    public double rawSecondsOfDay(
            Prayer prayer, CalculationCandidate candidate, Coordinates coordinates,
            ZoneId zone, LocalDate date) {
        Objects.requireNonNull(prayer, "prayer");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(date, "date");

        ZoneOffset offset = zone.getRules().getOffset(date.atTime(LocalTime.NOON));
        double julianDate = julianDateCalculator.calculate(date, LocalTime.NOON, offset);
        SolarPosition solar =
                solarPositionCalculator.calculate(julianDate, coordinates.longitude(), offset);
        double declination = solar.declinationDegrees();
        double noonHours = solar.solarNoonDecimalHours();
        double latitude = coordinates.latitude();

        double eventHours = switch (prayer) {
            case FAJR -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.MORNING, -candidate.fajrAngle(), latitude, declination);
            case SUNRISE -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.MORNING, candidate.sunriseAltitudeDegrees(), latitude, declination);
            case DHUHR -> noonHours;
            case ASR -> {
                double asrAltitude = hourAngleCalculator.asrAltitudeDegrees(
                        candidate.asrShadowFactor(), latitude, declination);
                yield noonHours + hourAngleCalculator.hourOffsetHours(
                        Direction.EVENING, asrAltitude, latitude, declination);
            }
            case MAGHRIB -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.EVENING, candidate.maghribAltitudeDegrees(), latitude, declination);
            case ISHA -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.EVENING, -candidate.ishaAngle(), latitude, declination);
        };
        return eventHours * SECONDS_PER_HOUR;
    }

    /**
     * The whole-second adjustment the candidate applies to the given prayer.
     *
     * @param prayer    the prayer
     * @param candidate the parameter set
     * @return the adjustment in seconds (may be negative)
     */
    public int adjustmentSeconds(Prayer prayer, CalculationCandidate candidate) {
        return switch (prayer) {
            case FAJR -> candidate.fajrAdjustmentSeconds();
            case SUNRISE -> candidate.sunriseAdjustmentSeconds();
            case DHUHR -> candidate.dhuhrAdjustmentSeconds();
            case ASR -> candidate.asrAdjustmentSeconds();
            case MAGHRIB -> candidate.maghribAdjustmentSeconds();
            case ISHA -> candidate.ishaAdjustmentSeconds();
        };
    }

    /**
     * The published minute of day for one prayer, after applying the candidate's
     * adjustment and rounding under its operation order.
     *
     * @param prayer      the prayer
     * @param candidate   the parameter set
     * @param coordinates the observer location
     * @param zone        the civil zone
     * @param date        the date
     * @return the published minute of day, in [0, 1439]
     */
    public int publishedMinuteOfDay(
            Prayer prayer, CalculationCandidate candidate, Coordinates coordinates,
            ZoneId zone, LocalDate date) {
        double rawSeconds = rawSecondsOfDay(prayer, candidate, coordinates, zone, date);
        return candidate.operationOrder().publishedMinuteOfDay(
                rawSeconds, adjustmentSeconds(prayer, candidate), candidate.roundingPolicy());
    }
}
