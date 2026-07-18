package io.github.cyonecoder.salatapi.secapproach.calculation;

/**
 * Immutable snapshot of the sun's position quantities needed to derive prayer
 * times for a given instant and place.
 *
 * <p>Ported verbatim from {@code domain.calculation.SolarPosition}; see
 * {@code docs/EXTRACTION_MAP.md}.
 *
 * @param julianDate            the Julian Date the values were computed for
 * @param declinationDegrees    the sun's declination, in degrees
 * @param equationOfTimeMinutes the equation of time, in minutes
 * @param solarNoonDecimalHours local clock time of solar noon, in decimal hours
 *                              (civil time of the supplied offset)
 */
public record SolarPosition(
        double julianDate,
        double declinationDegrees,
        double equationOfTimeMinutes,
        double solarNoonDecimalHours) {
}
