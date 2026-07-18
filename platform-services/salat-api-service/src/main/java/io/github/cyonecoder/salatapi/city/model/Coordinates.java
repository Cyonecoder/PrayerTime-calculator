package io.github.cyonecoder.salatapi.city.model;

/**
 * A geographic location.
 *
 * <p>Ported verbatim from {@code io.github.cyonecoder.prayertime.domain.model.Coordinates};
 * see {@code docs/EXTRACTION_MAP.md}.
 *
 * @param latitude        degrees north, -90..90
 * @param longitude       degrees east, -180..180
 * @param elevationMeters metres above sea level
 */
public record Coordinates(double latitude, double longitude, double elevationMeters) {

    public Coordinates {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireFinite(elevationMeters, "elevationMeters");
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }
}
