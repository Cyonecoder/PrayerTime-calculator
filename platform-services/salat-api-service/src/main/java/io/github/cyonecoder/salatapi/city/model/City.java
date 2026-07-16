package io.github.cyonecoder.salatapi.city.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * An application registry city: a named city with a location and civil time zone.
 *
 * <p>Immutable value object. The optional Habous city identifier links this city
 * to its entry in the official Moroccan (Habous) source when one exists — but the
 * authoritative application-to-ville link is the verified mapping resource, not
 * this field. See {@code ApplicationHabousMapping}.
 *
 * <p>Ported from {@code io.github.cyonecoder.prayertime.domain.model.City};
 * see {@code docs/EXTRACTION_MAP.md}.
 *
 * @param slug         stable machine identifier (e.g. {@code "casablanca"})
 * @param displayName  human-readable name (e.g. {@code "Casablanca"})
 * @param coordinates  geographic location
 * @param zone         civil time zone of the city (always {@code Africa/Casablanca})
 * @param habousCityId optional Habous source identifier; never {@code null}
 */
public record City(
        String slug,
        String displayName,
        Coordinates coordinates,
        ZoneId zone,
        Optional<String> habousCityId) {

    public City {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(habousCityId, "habousCityId (use Optional.empty())");
    }
}
