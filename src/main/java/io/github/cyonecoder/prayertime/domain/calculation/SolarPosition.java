package io.github.cyonecoder.prayertime.domain.calculation;

/**
 * Immutable snapshot of the sun's position quantities needed to derive prayer
 * times for a given instant and place.
 *
 * <p>All boundary values are in conventional units: degrees for the
 * declination, minutes for the equation of time, and decimal hours (0..24) for
 * local apparent solar noon.
 *
 * @param julianDate             the Julian Date the values were computed for
 * @param declinationDegrees     the sun's declination, in degrees
 * @param equationOfTimeMinutes  the equation of time, in minutes
 * @param solarNoonDecimalHours  local clock time of solar noon, in decimal
 *                               hours (civil time of the supplied offset)
 */
public record SolarPosition(
        double julianDate,
        double declinationDegrees,
        double equationOfTimeMinutes,
        double solarNoonDecimalHours) {
}
