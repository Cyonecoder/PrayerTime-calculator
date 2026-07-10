package io.github.cyonecoder.prayertime.infrastructure.city;

import com.google.gson.Gson;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory registry of major Moroccan cities, loaded and validated from the
 * bundled {@code /cities/morocco-cities.json} resource.
 *
 * <p>Lookups are by stable lowercase slug or by name/alias (case-insensitive).
 * An unknown city is <strong>never</strong> silently defaulted to Casablanca:
 * the {@code find*} methods return {@link Optional#empty()} and
 * {@link #requireById(String)} throws.
 *
 * <p>Habous city identifiers are carried as optional infrastructure metadata on
 * {@link City}; they are not fed into the mathematical engine. (When Habous IDs
 * are wired in, Salé is expected to share Rabat's official timetable.)
 */
public final class MoroccoCityRegistry {

    private static final String DEFAULT_RESOURCE = "/cities/morocco-cities.json";
    private static final ZoneId MOROCCO_ZONE = ZoneId.of("Africa/Casablanca");

    private final List<City> cities;
    private final Map<String, City> byId;
    private final Map<String, City> byNameOrAlias;

    /** Loads the registry from the bundled default resource. */
    public MoroccoCityRegistry() {
        this(DEFAULT_RESOURCE);
    }

    /**
     * Loads the registry from the given classpath resource.
     *
     * @param resourcePath absolute classpath resource path
     * @throws IllegalStateException if the resource is missing, malformed, or
     *                               contains duplicate slugs or conflicting
     *                               aliases, or invalid coordinates/zone
     */
    MoroccoCityRegistry(String resourcePath) {
        CitiesFile file = load(resourcePath);

        List<City> loaded = new ArrayList<>();
        Map<String, City> ids = new LinkedHashMap<>();
        Map<String, City> lookup = new LinkedHashMap<>();

        for (CityDto dto : file.cities) {
            City city = toCity(dto);

            if (ids.putIfAbsent(city.id(), city) != null) {
                throw new IllegalStateException("Duplicate city slug: " + city.id());
            }
            loaded.add(city);

            // Index the slug and the display name as lookup keys too.
            registerLookup(lookup, city.id(), city);
            registerLookup(lookup, city.displayName(), city);
            if (dto.aliases != null) {
                for (String alias : dto.aliases) {
                    registerLookup(lookup, alias, city);
                }
            }
        }

        this.cities = List.copyOf(loaded);
        this.byId = Map.copyOf(ids);
        this.byNameOrAlias = Map.copyOf(lookup);
    }

    /**
     * @param id a city slug (case-insensitive)
     * @return the city, or empty if unknown
     */
    public Optional<City> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(normalize(id)));
    }

    /**
     * @param value a slug, official name, or alias (case-insensitive)
     * @return the matching city, or empty if none
     */
    public Optional<City> findByNameOrAlias(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byNameOrAlias.get(normalize(value)));
    }

    /** @return all registered cities, in file order (immutable) */
    public List<City> all() {
        return cities;
    }

    /**
     * @param id a city slug (case-insensitive)
     * @return the city
     * @throws NoSuchElementException if the slug is unknown (never defaults)
     */
    public City requireById(String id) {
        return findById(id).orElseThrow(() ->
                new NoSuchElementException("Unknown Moroccan city id: " + id));
    }

    private City toCity(CityDto dto) {
        Objects.requireNonNull(dto.slug, "slug");
        Objects.requireNonNull(dto.displayName, "displayName for " + dto.slug);
        String zoneId = Objects.requireNonNull(dto.zoneId, "zoneId for " + dto.slug);
        if (!MOROCCO_ZONE.equals(ZoneId.of(zoneId))) {
            throw new IllegalStateException(
                    "City " + dto.slug + " must use " + MOROCCO_ZONE + " but was " + zoneId);
        }
        // Coordinates validates latitude/longitude range and finiteness.
        Coordinates coordinates =
                new Coordinates(dto.latitude, dto.longitude, dto.elevationMeters);
        return new City(
                normalize(dto.slug),
                dto.displayName,
                coordinates,
                MOROCCO_ZONE,
                Optional.ofNullable(dto.habousCityId));
    }

    private void registerLookup(Map<String, City> lookup, String key, City city) {
        String normalized = normalize(key);
        City existing = lookup.get(normalized);
        if (existing != null && !existing.id().equals(city.id())) {
            throw new IllegalStateException(
                    "Conflicting alias '" + normalized + "' maps to both "
                            + existing.id() + " and " + city.id());
        }
        lookup.put(normalized, city);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private CitiesFile load(String resourcePath) {
        try (InputStream in = MoroccoCityRegistry.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("City resource not found: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                CitiesFile file = new Gson().fromJson(reader, CitiesFile.class);
                if (file == null || file.cities == null || file.cities.isEmpty()) {
                    throw new IllegalStateException("No cities in resource: " + resourcePath);
                }
                return file;
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read city resource: " + resourcePath, e);
        }
    }

    /** JSON binding types (populated by Gson). */
    private static final class CitiesFile {
        List<CityDto> cities;
    }

    private static final class CityDto {
        String slug;
        String displayName;
        double latitude;
        double longitude;
        double elevationMeters;
        String zoneId;
        String habousCityId;
        List<String> aliases;
    }
}
