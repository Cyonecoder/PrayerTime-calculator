package io.github.cyonecoder.salatapi.secapproach.calculation;

import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Computes solar declination, the equation of time, and local solar noon from a
 * Julian Date.
 *
 * <h2>Sign conventions</h2>
 * <ul>
 *   <li><b>Longitude</b> is in degrees, <b>east-positive</b> (so Moroccan
 *       longitudes near -6°..-8° are negative/west). East longitudes make solar
 *       noon occur <em>earlier</em>, hence the {@code - longitude/15} term.</li>
 *   <li><b>UTC offset</b> is the civil offset the result is expressed in (e.g.
 *       +01:00 for Morocco). It is added <em>once</em>, purely to convert solar
 *       noon from Universal Time to civil time.</li>
 * </ul>
 *
 * <h2>Avoiding a double offset</h2>
 * The supplied {@code julianDate} is expected to already be in Universal Time
 * ({@link JulianDateCalculator} subtracts the offset when producing it). This
 * calculator therefore uses the Julian Date <em>as-is</em> for declination and
 * the equation of time, and applies the offset only to
 * {@link SolarPosition#solarNoonDecimalHours()}. The offset is never subtracted
 * from the Julian Date here, so it cannot be applied twice.
 *
 * <p>The formulae are carried over verbatim from
 * {@code domain.calculation.SolarPositionCalculator} — they are the series the
 * released sec-approach offsets were fitted against, so any "improvement" here
 * would silently invalidate the offsets. See {@code docs/EXTRACTION_MAP.md}.
 */
public final class SolarPositionCalculator {

    private static final double J2000_EPOCH = 2451545.0;
    private static final double DAYS_PER_JULIAN_YEAR = 365.25;
    private static final double DAYS_PER_JULIAN_CENTURY = 36525.0;
    private static final double DEGREES_PER_HOUR = 15.0;
    private static final double MINUTES_PER_HOUR = 60.0;
    private static final double SECONDS_PER_HOUR = 3600.0;

    /** Approximate radians-to-degrees factor used by the legacy declination series. */
    private static final double RAD_TO_DEG_APPROX = 57.297;

    /**
     * @param julianDate       the Julian Date in Universal Time
     * @param longitudeDegrees observer longitude, east-positive degrees
     * @param offset           the civil UTC offset to express solar noon in
     * @return an immutable {@link SolarPosition}
     */
    public SolarPosition calculate(double julianDate, double longitudeDegrees, ZoneOffset offset) {
        Objects.requireNonNull(offset, "offset");
        requireFinite(julianDate, "julianDate");
        requireFinite(longitudeDegrees, "longitudeDegrees");

        double declinationDegrees = declinationDegrees(julianDate);
        double equationOfTimeMinutes = equationOfTimeMinutes(julianDate);
        double solarNoonDecimalHours =
                solarNoonDecimalHours(longitudeDegrees, equationOfTimeMinutes, offset);

        return new SolarPosition(
                julianDate, declinationDegrees, equationOfTimeMinutes, solarNoonDecimalHours);
    }

    /** Sun's declination in degrees, from the legacy trigonometric series. */
    private double declinationDegrees(double julianDate) {
        double t = 2.0 * Math.PI * (julianDate - J2000_EPOCH) / DAYS_PER_JULIAN_YEAR;
        return 0.37877
                + 23.264 * Math.sin(Math.toRadians(RAD_TO_DEG_APPROX * t - 79.547))
                + 0.3812 * Math.sin(Math.toRadians(2 * RAD_TO_DEG_APPROX * t - 82.682))
                + 0.17132 * Math.sin(Math.toRadians(3 * RAD_TO_DEG_APPROX * t - 59.722));
    }

    /** Equation of time in minutes, from the legacy series (originally in milli-minutes). */
    private double equationOfTimeMinutes(double julianDate) {
        double u = (julianDate - J2000_EPOCH) / DAYS_PER_JULIAN_CENTURY;
        double meanLongitude = 280.46607 + 36000.7698 * u;
        double milliMinutes =
                -(1789 + 237 * u) * Math.sin(Math.toRadians(meanLongitude))
                        - (7146 - 62 * u) * Math.cos(Math.toRadians(meanLongitude))
                        + (9934 - 14 * u) * Math.sin(Math.toRadians(2 * meanLongitude))
                        - (29 + 5 * u) * Math.cos(Math.toRadians(2 * meanLongitude))
                        + (74 + 10 * u) * Math.sin(Math.toRadians(3 * meanLongitude))
                        + (320 - 4 * u) * Math.cos(Math.toRadians(3 * meanLongitude))
                        - 212 * Math.sin(Math.toRadians(4 * meanLongitude));
        return milliMinutes / 1000.0;
    }

    /**
     * Local apparent solar noon in decimal hours: 12:00 shifted by the civil
     * offset, the longitude term, and the equation of time. The offset is applied
     * exactly once here.
     */
    private double solarNoonDecimalHours(
            double longitudeDegrees, double equationOfTimeMinutes, ZoneOffset offset) {
        double offsetHours = offset.getTotalSeconds() / SECONDS_PER_HOUR;
        return 12.0
                + offsetHours
                - (longitudeDegrees / DEGREES_PER_HOUR)
                - (equationOfTimeMinutes / MINUTES_PER_HOUR);
    }

    private static void requireFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number but was " + value);
        }
    }
}
