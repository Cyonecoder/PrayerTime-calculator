package io.github.cyonecoder.prayertime.domain.calculation;

/**
 * Computes solar hour angles for the events used to derive prayer times, plus
 * the Asr sun altitude.
 *
 * <h2>Sign conventions (all in degrees at the boundary)</h2>
 * <ul>
 *   <li><b>Latitude</b>: north-positive.</li>
 *   <li><b>Declination</b>: north-positive.</li>
 *   <li><b>Altitude</b>: above the horizon is positive. Twilight events use a
 *       negative altitude (a depression), e.g. Fajr altitude = {@code -19}.</li>
 *   <li><b>Event direction</b>: {@link Direction#MORNING} events happen before
 *       solar noon (hour offset is negative); {@link Direction#EVENING} events
 *       happen after (positive). Sunrise is a morning event and sunset an
 *       evening event, both at the sunrise/sunset altitude.</li>
 * </ul>
 *
 * <p>The hour angle {@code H} satisfies
 * {@code cos H = (sin(alt) - sin(lat) sin(decl)) / (cos(lat) cos(decl))}.
 * The cosine is clamped to {@code [-1, 1]} for floating-point safety so the
 * result is never {@code NaN}; a value that lies clearly outside that range
 * means the event physically cannot occur (polar day/night, or a twilight
 * angle that is never reached) and raises {@link EventDoesNotOccurException}.
 */
public final class HourAngleCalculator {

    /** Cosine values within this tolerance of [-1, 1] are treated as clampable rounding error. */
    private static final double CLAMP_TOLERANCE = 1e-6;
    private static final double DEGREES_PER_HOUR = 15.0;

    /** Whether an event is timed before or after solar noon. */
    public enum Direction {
        /** Before solar noon; the hour offset is negative. */
        MORNING(-1),
        /** After solar noon; the hour offset is positive. */
        EVENING(+1);

        private final int sign;

        Direction(int sign) {
            this.sign = sign;
        }
    }

    /** Raised when the requested solar event does not occur for the given inputs. */
    public static final class EventDoesNotOccurException extends RuntimeException {
        EventDoesNotOccurException(double cosineOfHourAngle) {
            super("Solar event does not occur: cos(H) = " + cosineOfHourAngle
                    + " lies outside [-1, 1]");
        }
    }

    /**
     * The raw cosine of the hour angle for the sun at the given altitude.
     *
     * @param altitudeDegrees    sun altitude (negative for depression)
     * @param latitudeDegrees    observer latitude, north-positive
     * @param declinationDegrees solar declination, north-positive
     * @return the (unclamped) cosine of the hour angle
     */
    public double cosineOfHourAngle(
            double altitudeDegrees, double latitudeDegrees, double declinationDegrees) {
        double alt = Math.toRadians(altitudeDegrees);
        double lat = Math.toRadians(latitudeDegrees);
        double decl = Math.toRadians(declinationDegrees);
        return (Math.sin(alt) - Math.sin(lat) * Math.sin(decl))
                / (Math.cos(lat) * Math.cos(decl));
    }

    /**
     * @return {@code true} if the event physically occurs (its hour-angle
     *         cosine lies within [-1, 1] to within tolerance)
     */
    public boolean eventOccurs(
            double altitudeDegrees, double latitudeDegrees, double declinationDegrees) {
        double cosine = cosineOfHourAngle(altitudeDegrees, latitudeDegrees, declinationDegrees);
        return cosine >= -1.0 - CLAMP_TOLERANCE && cosine <= 1.0 + CLAMP_TOLERANCE;
    }

    /**
     * Hour angle magnitude in degrees from a cosine value. The input is clamped
     * to {@code [-1, 1]} for numerical safety; a value further than the clamp
     * tolerance outside that range raises {@link EventDoesNotOccurException}
     * instead of returning {@code NaN}.
     *
     * @param cosineOfHourAngle the cosine of the hour angle
     * @return the hour angle in degrees, in {@code [0, 180]}
     * @throws EventDoesNotOccurException if the event cannot occur
     */
    public double hourAngleFromCosineDegrees(double cosineOfHourAngle) {
        if (cosineOfHourAngle > 1.0 + CLAMP_TOLERANCE
                || cosineOfHourAngle < -1.0 - CLAMP_TOLERANCE) {
            throw new EventDoesNotOccurException(cosineOfHourAngle);
        }
        double clamped = Math.max(-1.0, Math.min(1.0, cosineOfHourAngle));
        return Math.toDegrees(Math.acos(clamped));
    }

    /**
     * Hour angle magnitude in degrees for the sun at the given altitude.
     *
     * @param altitudeDegrees    sun altitude (negative for depression)
     * @param latitudeDegrees    observer latitude, north-positive
     * @param declinationDegrees solar declination, north-positive
     * @return the hour angle in degrees, in {@code [0, 180]}
     * @throws EventDoesNotOccurException if the event cannot occur
     */
    public double hourAngleDegrees(
            double altitudeDegrees, double latitudeDegrees, double declinationDegrees) {
        return hourAngleFromCosineDegrees(
                cosineOfHourAngle(altitudeDegrees, latitudeDegrees, declinationDegrees));
    }

    /**
     * Signed offset from solar noon, in decimal hours, for a morning or evening
     * event at the given altitude. Add this to solar noon to obtain the event
     * time.
     *
     * @param direction          morning (before noon) or evening (after noon)
     * @param altitudeDegrees    sun altitude (negative for depression)
     * @param latitudeDegrees    observer latitude, north-positive
     * @param declinationDegrees solar declination, north-positive
     * @return the signed offset from solar noon in hours
     * @throws EventDoesNotOccurException if the event cannot occur
     */
    public double hourOffsetHours(
            Direction direction,
            double altitudeDegrees,
            double latitudeDegrees,
            double declinationDegrees) {
        double hours = hourAngleDegrees(altitudeDegrees, latitudeDegrees, declinationDegrees)
                / DEGREES_PER_HOUR;
        return direction.sign * hours;
    }

    /**
     * Sun altitude (degrees above the horizon) at the standard Asr moment, when
     * an object's shadow equals {@code shadowFactor} times its length plus the
     * shadow it cast at local noon.
     *
     * <p>The Asr school is selected purely by the configured
     * {@code shadowFactor} (1 for the majority/Shafi'i, 2 for Hanafi); there is
     * no hard-coded factor.
     *
     * @param shadowFactor       the configured Asr shadow-length factor (&gt; 0)
     * @param latitudeDegrees    observer latitude, north-positive
     * @param declinationDegrees solar declination, north-positive
     * @return the Asr sun altitude in degrees
     */
    public double asrAltitudeDegrees(
            double shadowFactor, double latitudeDegrees, double declinationDegrees) {
        double noonZenithDistance = Math.toRadians(Math.abs(latitudeDegrees - declinationDegrees));
        double cotAltitude = shadowFactor + Math.tan(noonZenithDistance);
        return Math.toDegrees(Math.atan(1.0 / cotAltitude));
    }
}
