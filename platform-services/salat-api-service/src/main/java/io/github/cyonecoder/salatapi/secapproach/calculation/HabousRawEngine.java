package io.github.cyonecoder.salatapi.secapproach.calculation;

import io.github.cyonecoder.salatapi.city.model.Coordinates;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.secapproach.calculation.HourAngleCalculator.Direction;
import io.github.cyonecoder.salatapi.secapproach.model.CalculationCandidate;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Computes the <em>raw</em> (un-adjusted, un-rounded) prayer event time in seconds
 * of the day for a parameter set, then applies the parameters' adjustment and
 * rounding to obtain the published minute.
 *
 * <p>Ported verbatim from {@code audit.optimization.HabousRawEngine}; see
 * {@code docs/EXTRACTION_MAP.md}. It duplicates none of the formulae — the
 * astronomical components are the ported {@link JulianDateCalculator},
 * {@link SolarPositionCalculator} and {@link HourAngleCalculator}.
 */
@Component
public class HabousRawEngine {

    private static final double SECONDS_PER_HOUR = 3600.0;

    /**
     * Horizon-dip coefficient (degrees per √metre) for the elevation-adjusted
     * horizon family. Matches the widely-used PrayTimes convention
     * {@code angle = 0.833 + 0.0347·√elevation}, applied as a global function of
     * the observer's elevation (never a per-city constant).
     */
    private static final double ELEVATION_DIP_COEFFICIENT = 0.0347;

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
        double sunriseDip = horizonDipDegrees(
                candidate, coordinates, candidate.sunriseHorizonDipCoefficient());
        double maghribDip = horizonDipDegrees(
                candidate, coordinates, candidate.maghribHorizonDipCoefficient());

        double eventHours = switch (prayer) {
            case FAJR -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.MORNING, -candidate.fajrAngle(), latitude, declination);
            case SUNRISE -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.MORNING, candidate.sunriseAltitudeDegrees() - sunriseDip,
                    latitude, declination);
            case DHUHR -> noonHours;
            case ASR -> {
                double asrAltitude = hourAngleCalculator.asrAltitudeDegrees(
                        candidate.asrShadowFactor(), latitude, declination);
                yield noonHours + hourAngleCalculator.hourOffsetHours(
                        Direction.EVENING, asrAltitude, latitude, declination);
            }
            case MAGHRIB -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.EVENING, candidate.maghribAltitudeDegrees() - maghribDip,
                    latitude, declination);
            case ISHA -> noonHours + hourAngleCalculator.hourOffsetHours(
                    Direction.EVENING, -candidate.ishaAngle(), latitude, declination);
        };
        return eventHours * SECONDS_PER_HOUR;
    }

    /**
     * The horizon dip (in degrees, non-negative) subtracted from a sunrise or
     * Maghrib altitude for the observer's elevation.
     *
     * <p>Precedence: a non-zero per-event fitted {@code eventCoefficient} (deg/√m)
     * is used directly ({@code coefficient·√elevation}); otherwise, if the
     * candidate enables the fixed
     * {@link CalculationCandidate#elevationAdjustedHorizon()} family, the standard
     * PrayTimes coefficient is used; otherwise the dip is zero (sea-level).
     */
    private static double horizonDipDegrees(
            CalculationCandidate candidate, Coordinates coordinates, double eventCoefficient) {
        double elevation = Math.max(0.0, coordinates.elevationMeters());
        double coefficient;
        if (eventCoefficient > 0.0) {
            coefficient = eventCoefficient;
        } else if (candidate.elevationAdjustedHorizon()) {
            coefficient = ELEVATION_DIP_COEFFICIENT;
        } else {
            return 0.0;
        }
        return coefficient * Math.sqrt(elevation);
    }

    /**
     * @param prayer    the prayer
     * @param candidate the parameter set
     * @return the whole-second adjustment for the prayer (may be negative)
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
