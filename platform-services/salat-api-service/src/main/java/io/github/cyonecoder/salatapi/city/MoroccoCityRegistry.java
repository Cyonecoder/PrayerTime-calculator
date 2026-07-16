package io.github.cyonecoder.salatapi.city;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyonecoder.salatapi.city.model.City;
import io.github.cyonecoder.salatapi.city.model.Coordinates;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Group 8.2 — the application registry: in-memory registry of Moroccan cities,
 * loaded and validated from the bundled {@code /cities/morocco-cities.json}
 * resource. Every city carries a canonical slug, coordinates, aliases and the
 * {@code Africa/Casablanca} zone.
 *
 * <p>Lookups are by stable lowercase slug or by name/alias (case-insensitive).
 * An unknown city is <strong>never</strong> silently defaulted to Casablanca:
 * the {@code find*} methods return {@link Optional#empty()} and
 * {@link #requireBySlug(String)} throws.
 *
 * <p>Membership here means the city is <strong>known</strong>, not that
 * sec-approach supports it — only 48 of these 67 have offsets. See
 * {@code CitySupportService}.
 *
 * <p>Ported from {@code io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry}
 * with Gson replaced by Jackson; see {@code docs/EXTRACTION_MAP.md}.
 */
@Component
public class MoroccoCityRegistry {

    /** The expected registry size; a mismatch fails startup rather than serving a partial registry. */
    public static final int EXPECTED_CITY_COUNT = 67;

    private static final String DEFAULT_RESOURCE = "/cities/morocco-cities.json";
    private static final ZoneId MOROCCO_ZONE = ZoneId.of("Africa/Casablanca");

    private final List<City> cities;
    private final Map<String, City> bySlug;
    private final Map<String, City> byNameOrAlias;

    /** Loads the registry from the bundled default resource. */
    public MoroccoCityRegistry() {
        this(DEFAULT_RESOURCE);
    }

    /**
     * @param resourcePath absolute classpath resource path
     * @throws IllegalStateException if the resource is missing, malformed, holds
     *                               an unexpected number of cities, or contains
     *                               duplicate slugs or conflicting aliases
     */
    MoroccoCityRegistry(String resourcePath) {
        CitiesFile file = load(resourcePath);

        List<City> loaded = new ArrayList<>();
        Map<String, City> slugs = new LinkedHashMap<>();
        Map<String, City> lookup = new LinkedHashMap<>();

        for (CityDto dto : file.cities()) {
            City city = toCity(dto);

            if (slugs.putIfAbsent(city.slug(), city) != null) {
                throw new IllegalStateException("Duplicate city slug: " + city.slug());
            }
            loaded.add(city);

            registerLookup(lookup, city.slug(), city);
            registerLookup(lookup, city.displayName(), city);
            if (dto.aliases() != null) {
                for (String alias : dto.aliases()) {
                    registerLookup(lookup, alias, city);
                }
            }
        }

        if (loaded.size() != EXPECTED_CITY_COUNT) {
            throw new IllegalStateException(
                    "Expected " + EXPECTED_CITY_COUNT + " registry cities in " + resourcePath
                            + " but found " + loaded.size());
        }

        this.cities = List.copyOf(loaded);
        this.bySlug = Map.copyOf(slugs);
        this.byNameOrAlias = Map.copyOf(lookup);
    }

    /**
     * @param slug a city slug (case-insensitive)
     * @return the city, or empty if unknown
     */
    public Optional<City> findBySlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySlug.get(normalize(slug)));
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

    /** @return the number of registry cities (67) */
    public int count() {
        return cities.size();
    }

    /**
     * @param slug a city slug (case-insensitive)
     * @return the city
     * @throws NoSuchElementException if the slug is unknown (never defaults)
     */
    public City requireBySlug(String slug) {
        return findBySlug(slug).orElseThrow(() ->
                new NoSuchElementException("Unknown Moroccan city slug: " + slug));
    }

    private City toCity(CityDto dto) {
        Objects.requireNonNull(dto.slug(), "slug");
        Objects.requireNonNull(dto.displayName(), "displayName for " + dto.slug());
        String zoneId = Objects.requireNonNull(dto.zoneId(), "zoneId for " + dto.slug());
        if (!MOROCCO_ZONE.equals(ZoneId.of(zoneId))) {
            throw new IllegalStateException(
                    "City " + dto.slug() + " must use " + MOROCCO_ZONE + " but was " + zoneId);
        }
        Coordinates coordinates =
                new Coordinates(dto.latitude(), dto.longitude(), dto.elevationMeters());
        return new City(
                normalize(dto.slug()),
                dto.displayName(),
                coordinates,
                MOROCCO_ZONE,
                Optional.ofNullable(dto.habousCityId()));
    }

    private void registerLookup(Map<String, City> lookup, String key, City city) {
        String normalized = normalize(key);
        City existing = lookup.get(normalized);
        if (existing != null && !existing.slug().equals(city.slug())) {
            throw new IllegalStateException(
                    "Conflicting alias '" + normalized + "' maps to both "
                            + existing.slug() + " and " + city.slug());
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
            CitiesFile file = new ObjectMapper().readValue(in, CitiesFile.class);
            if (file == null || file.cities() == null || file.cities().isEmpty()) {
                throw new IllegalStateException("No cities in resource: " + resourcePath);
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read city resource: " + resourcePath, e);
        }
    }

    /** JSON binding types (populated by Jackson). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CitiesFile(List<CityDto> cities) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CityDto(
            String slug,
            String displayName,
            double latitude,
            double longitude,
            double elevationMeters,
            String zoneId,
            String habousCityId,
            List<String> aliases) {}
}
