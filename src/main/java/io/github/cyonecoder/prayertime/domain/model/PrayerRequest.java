package io.github.cyonecoder.prayertime.domain.model;

import io.github.cyonecoder.prayertime.domain.method.CalculationMethod;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * An input request describing what to calculate: for which date, location,
 * time zone and calculation method.
 *
 * <p>Immutable value object. The optional elevation override, when present,
 * replaces {@link Coordinates#elevationMeters()} for this calculation (for
 * example to match a published reference that assumes sea level).
 *
 * @param date                    the civil date to calculate for
 * @param coordinates             the location
 * @param zone                    the civil time zone to express results in
 * @param method                  the calculation method to apply
 * @param elevationOverrideMeters optional elevation override in metres; never
 *                                {@code null} (use
 *                                {@link OptionalDouble#empty()} for none)
 */
public record PrayerRequest(
        LocalDate date,
        Coordinates coordinates,
        ZoneId zone,
        CalculationMethod method,
        OptionalDouble elevationOverrideMeters) {

    /**
     * Validates that all mandatory components are present.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public PrayerRequest {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(elevationOverrideMeters,
                "elevationOverrideMeters (use OptionalDouble.empty())");
    }

    /**
     * Convenience factory for a request without an elevation override.
     *
     * @param date        the civil date to calculate for
     * @param coordinates the location
     * @param zone        the civil time zone
     * @param method      the calculation method
     * @return a {@code PrayerRequest} with no elevation override
     */
    public static PrayerRequest of(
            LocalDate date, Coordinates coordinates, ZoneId zone, CalculationMethod method) {
        return new PrayerRequest(date, coordinates, zone, method, OptionalDouble.empty());
    }

    /**
     * The elevation to use for this calculation: the override when present,
     * otherwise the coordinate's elevation.
     *
     * @return the effective elevation in metres
     */
    public double effectiveElevationMeters() {
        return elevationOverrideMeters.orElse(coordinates.elevationMeters());
    }
}
