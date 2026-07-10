package io.github.cyonecoder.prayertime.domain.model;

/**
 * A geographic location used for prayer-time calculations.
 *
 * <p>Immutable value object. Latitude must be within [-90, 90] and longitude
 * within [-180, 180]. All components must be finite (NaN and infinities are
 * rejected). Elevation is expressed in metres above sea level and may be zero
 * or negative (e.g. locations below sea level).
 *
 * @param latitude       latitude in decimal degrees, [-90, 90]
 * @param longitude      longitude in decimal degrees, [-180, 180]
 * @param elevationMeters elevation above sea level in metres (may be &le; 0)
 */
public record Coordinates(double latitude, double longitude, double elevationMeters) {

    /**
     * Validates the coordinate components.
     *
     * @throws IllegalArgumentException if any component is NaN/infinite, or if
     *                                  latitude or longitude is out of range
     */
    public Coordinates {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireFinite(elevationMeters, "elevationMeters");
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException(
                    "latitude must be within [-90, 90] but was " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException(
                    "longitude must be within [-180, 180] but was " + longitude);
        }
    }

    private static void requireFinite(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number but was " + value);
        }
    }
}
