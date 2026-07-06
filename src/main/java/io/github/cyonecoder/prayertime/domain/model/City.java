package io.github.cyonecoder.prayertime.domain.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * A named city with a location and civil time zone.
 *
 * <p>Immutable value object. The optional Habous city identifier links this
 * city to its entry in the official Moroccan (Habous) prayer-time source when
 * one exists.
 *
 * @param id           stable machine identifier (e.g. {@code "casablanca"})
 * @param displayName  human-readable name (e.g. {@code "Casablanca"})
 * @param coordinates  geographic location
 * @param zone         civil time zone of the city
 * @param habousCityId optional Habous source identifier; never {@code null}
 *                     (use {@link Optional#empty()} when unknown)
 */
public record City(
        String id,
        String displayName,
        Coordinates coordinates,
        ZoneId zone,
        Optional<String> habousCityId) {

    /**
     * Validates that all mandatory components are present.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public City {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(habousCityId, "habousCityId (use Optional.empty())");
    }

    /**
     * Convenience factory for a city without a Habous identifier.
     *
     * @param id          stable machine identifier
     * @param displayName human-readable name
     * @param coordinates geographic location
     * @param zone        civil time zone
     * @return a {@code City} whose {@link #habousCityId()} is empty
     */
    public static City of(String id, String displayName, Coordinates coordinates, ZoneId zone) {
        return new City(id, displayName, coordinates, zone, Optional.empty());
    }
}
