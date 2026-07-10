package io.github.cyonecoder.prayertime.domain.calculation;

import io.github.cyonecoder.prayertime.domain.calculation.HourAngleCalculator.Direction;
import io.github.cyonecoder.prayertime.domain.method.CalculationParameters;
import io.github.cyonecoder.prayertime.domain.method.ElevationPolicy;
import io.github.cyonecoder.prayertime.domain.method.PrayerAdjustments;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Pure, deterministic prayer-time engine built from the extracted components
 * ({@link JulianDateCalculator}, {@link SolarPositionCalculator},
 * {@link HourAngleCalculator}, {@link TimeConversion}).
 *
 * <h2>Offset handling</h2>
 * The engine never reads the current date or the JVM default zone. For each
 * request it resolves the {@link ZoneOffset} <em>once</em>, from the request's
 * {@link ZoneId} evaluated at local noon (the same rule used by
 * {@code MoroccoCivilTimeService}, so an {@code Africa/Casablanca} request
 * automatically picks up the +01:00 summer offset or the +00:00 Ramadan
 * suspension). That single offset is threaded through the Julian-date and
 * solar-noon steps; no extra summer-time hour is ever added afterwards.
 *
 * <p>The result is rounded consistently to whole minutes, and the six times are
 * validated to be strictly increasing before being returned.
 */
public final class PrayerTimeCalculator {

    /** Legacy horizon-dip coefficient: extra depression per sqrt(metres) of elevation. */
    private static final double ELEVATION_DIP_COEFFICIENT = 0.0347;
    private static final double MINUTES_PER_HOUR = 60.0;
    private static final double OFFICIAL_TABLE_ELEVATION_METERS = 0.0;

    private final JulianDateCalculator julianDateCalculator;
    private final SolarPositionCalculator solarPositionCalculator;
    private final HourAngleCalculator hourAngleCalculator;
    private final TimeConversion timeConversion;

    /** Raised when the computed six times are not strictly increasing. */
    public static final class InvalidPrayerOrderingException extends RuntimeException {
        private final transient List<Prayer> affectedPrayers;

        InvalidPrayerOrderingException(Prayer earlier, LocalTime earlierTime,
                                       Prayer later, LocalTime laterTime) {
            super("Invalid prayer ordering: " + earlier + " (" + earlierTime + ") must be before "
                    + later + " (" + laterTime + ")");
            this.affectedPrayers = List.of(earlier, later);
        }

        /** @return the two prayers whose ordering was violated, earlier first */
        public List<Prayer> affectedPrayers() {
            return affectedPrayers;
        }
    }

    public PrayerTimeCalculator(
            JulianDateCalculator julianDateCalculator,
            SolarPositionCalculator solarPositionCalculator,
            HourAngleCalculator hourAngleCalculator,
            TimeConversion timeConversion) {
        this.julianDateCalculator = Objects.requireNonNull(julianDateCalculator, "julianDateCalculator");
        this.solarPositionCalculator = Objects.requireNonNull(solarPositionCalculator, "solarPositionCalculator");
        this.hourAngleCalculator = Objects.requireNonNull(hourAngleCalculator, "hourAngleCalculator");
        this.timeConversion = Objects.requireNonNull(timeConversion, "timeConversion");
    }

    /** Convenience constructor wiring the default component implementations. */
    public PrayerTimeCalculator() {
        this(new JulianDateCalculator(), new SolarPositionCalculator(),
                new HourAngleCalculator(), new TimeConversion());
    }

    /**
     * Resolves the civil UTC offset for a request, at local noon on the
     * requested date. Applied exactly once by {@link #calculate(PrayerRequest)}.
     *
     * @param request the request
     * @return the resolved offset
     */
    public ZoneOffset offsetFor(PrayerRequest request) {
        Objects.requireNonNull(request, "request");
        return resolveOffset(request.zone(), request.date());
    }

    /**
     * Computes the six prayer times for the given request.
     *
     * @param request the calculation request
     * @return the computed {@link PrayerTimes}, rounded to whole minutes
     * @throws NullPointerException            if {@code request} is {@code null}
     * @throws InvalidPrayerOrderingException  if the six times are not strictly
     *                                         increasing
     */
    public PrayerTimes calculate(PrayerRequest request) {
        Objects.requireNonNull(request, "request");

        LocalDate date = request.date();
        Coordinates coordinates = request.coordinates();
        CalculationParameters params = request.method().parameters();

        ZoneOffset offset = resolveOffset(request.zone(), date);

        // Julian date at local noon (offset applied here to reach Universal Time).
        double julianDate = julianDateCalculator.calculate(date, LocalTime.NOON, offset);

        // Solar noon is expressed in the same civil offset (its only other use).
        SolarPosition solar =
                solarPositionCalculator.calculate(julianDate, coordinates.longitude(), offset);
        double declination = solar.declinationDegrees();
        double solarNoonHours = solar.solarNoonDecimalHours();
        double latitude = coordinates.latitude();

        double elevationMeters = elevationFor(params.elevationPolicy(), request);
        double sunriseAltitude =
                params.sunriseAngle() - ELEVATION_DIP_COEFFICIENT * Math.sqrt(Math.max(0.0, elevationMeters));
        double asrAltitude =
                hourAngleCalculator.asrAltitudeDegrees(params.asrShadowFactor(), latitude, declination);

        // Raw astronomical times (decimal hours), before method adjustments.
        double fajrHours = solarNoonHours
                + hourAngleCalculator.hourOffsetHours(Direction.MORNING, -params.fajrAngle(), latitude, declination);
        double sunriseHours = solarNoonHours
                + hourAngleCalculator.hourOffsetHours(Direction.MORNING, sunriseAltitude, latitude, declination);
        double dhuhrHours = solarNoonHours;
        double asrHours = solarNoonHours
                + hourAngleCalculator.hourOffsetHours(Direction.EVENING, asrAltitude, latitude, declination);
        double maghribHours = solarNoonHours
                + hourAngleCalculator.hourOffsetHours(Direction.EVENING, sunriseAltitude, latitude, declination);
        double ishaHours = solarNoonHours
                + hourAngleCalculator.hourOffsetHours(Direction.EVENING, -params.ishaAngle(), latitude, declination);

        // Apply method-level minute adjustments after the raw calculation.
        PrayerAdjustments adj = params.adjustments();
        LocalTime fajr = toMinute(fajrHours, adj.fajr());
        LocalTime sunrise = toMinute(sunriseHours, adj.sunrise());
        LocalTime dhuhr = toMinute(dhuhrHours, adj.dhuhr());
        LocalTime asr = toMinute(asrHours, adj.asr());
        LocalTime maghrib = toMinute(maghribHours, adj.maghrib());
        LocalTime isha = toMinute(ishaHours, adj.isha());

        PrayerTimes times = new PrayerTimes(date, fajr, sunrise, dhuhr, asr, maghrib, isha);
        validateOrdering(times);
        return times;
    }

    private ZoneOffset resolveOffset(ZoneId zone, LocalDate date) {
        // Resolve at local noon so the value is never inside a transition gap/overlap.
        return zone.getRules().getOffset(date.atTime(LocalTime.NOON));
    }

    private double elevationFor(ElevationPolicy policy, PrayerRequest request) {
        return switch (policy) {
            case OFFICIAL_CITY_TABLE -> OFFICIAL_TABLE_ELEVATION_METERS;
            case OBSERVER_ELEVATION -> request.effectiveElevationMeters();
        };
    }

    private LocalTime toMinute(double decimalHours, int adjustmentMinutes) {
        return timeConversion.toLocalTimeRoundedToMinute(decimalHours + adjustmentMinutes / MINUTES_PER_HOUR);
    }

    /**
     * Verifies the six times are strictly increasing in canonical order.
     *
     * @param times the computed times
     * @throws InvalidPrayerOrderingException on the first violation, naming the
     *                                        two prayers involved
     */
    static void validateOrdering(PrayerTimes times) {
        Prayer[] order = {
                Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA
        };
        for (int i = 0; i < order.length - 1; i++) {
            LocalTime earlier = times.get(order[i]);
            LocalTime later = times.get(order[i + 1]);
            if (!earlier.isBefore(later)) {
                throw new InvalidPrayerOrderingException(order[i], earlier, order[i + 1], later);
            }
        }
    }
}
