package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves a dataset city slug to the coordinates and civil zone the search
 * feeds into the astronomical engine.
 *
 * <p>Backed by the production {@link MoroccoCityRegistry} (read-only); the
 * registry and its bundled resource are never modified. Extra locations can be
 * registered explicitly for tests.
 */
public final class CityLocations {

    /**
     * A resolved location.
     *
     * @param coordinates the observer coordinates
     * @param zone        the civil time zone
     */
    public record Location(Coordinates coordinates, ZoneId zone) {
    }

    private final MoroccoCityRegistry registry;
    private final Map<String, Location> overrides = new HashMap<>();

    public CityLocations() {
        this(new MoroccoCityRegistry());
    }

    public CityLocations(MoroccoCityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Registers or overrides a location for a slug (used by tests that supply
     * synthetic cities).
     *
     * @param slug     city slug
     * @param location the location
     * @return this, for chaining
     */
    public CityLocations register(String slug, Location location) {
        overrides.put(slug.toLowerCase(java.util.Locale.ROOT), location);
        return this;
    }

    /**
     * Resolves a city slug.
     *
     * @param slug the city slug
     * @return the location
     * @throws IllegalArgumentException if the slug is unknown
     */
    public Location resolve(String slug) {
        String key = slug.toLowerCase(java.util.Locale.ROOT);
        Location override = overrides.get(key);
        if (override != null) {
            return override;
        }
        City city = registry.findById(key)
                .or(() -> registry.findByNameOrAlias(key))
                .orElseThrow(() -> new IllegalArgumentException("Unknown city in dataset: " + slug));
        return new Location(city.coordinates(), city.zone());
    }
}
