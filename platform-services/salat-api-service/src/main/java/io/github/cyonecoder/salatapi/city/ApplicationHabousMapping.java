package io.github.cyonecoder.salatapi.city;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Group 8.3 — the verified mappings connecting an application city slug to an
 * official Habous ville id. Loaded from the bundled
 * {@code /cities/habous-city-mapping.json}.
 *
 * <p>Only <strong>verified</strong> mappings are admitted: each was confirmed
 * against the live site by selecting the ville and reading back its timetable.
 * An unverified or absent mapping means the city has no official data path, and
 * the service must say so rather than guess a ville id.
 *
 * <p>These 60 are a subset of the 67 registry cities, and their ville targets
 * are a subset of the 191 official villes. 12 of the 60 have no sec-approach
 * offsets — they are official-only. See {@code CitySupportService}.
 */
@Component
public class ApplicationHabousMapping {

    /** The expected number of verified mappings; matches {@code generatedFrom.verifiedCityCount} in the sec-approach resource. */
    public static final int EXPECTED_MAPPING_COUNT = 60;

    private static final String DEFAULT_RESOURCE = "/cities/habous-city-mapping.json";

    /**
     * One verified application-to-ville link.
     *
     * @param applicationCityId the application city slug
     * @param habousCityId      the official ville id
     * @param habousArabicName  the exact Arabic name of that ville
     */
    public record Mapping(String applicationCityId, String habousCityId, String habousArabicName) {
        public Mapping {
            Objects.requireNonNull(applicationCityId, "applicationCityId");
            Objects.requireNonNull(habousCityId, "habousCityId");
            Objects.requireNonNull(habousArabicName, "habousArabicName");
        }
    }

    private final List<Mapping> mappings;
    private final Map<String, Mapping> byApplicationCityId;
    private final Map<String, Mapping> byHabousCityId;

    public ApplicationHabousMapping() {
        this(DEFAULT_RESOURCE);
    }

    ApplicationHabousMapping(String resourcePath) {
        MappingFile file = load(resourcePath);

        List<Mapping> loaded = new ArrayList<>();
        Map<String, Mapping> byApp = new LinkedHashMap<>();
        Map<String, Mapping> byVille = new LinkedHashMap<>();

        for (MappingDto dto : file.mappings()) {
            if (!Boolean.TRUE.equals(dto.verified())) {
                throw new IllegalStateException(
                        "Unverified mapping for " + dto.applicationCityId()
                                + "; only verified mappings may be loaded");
            }
            Mapping mapping = new Mapping(
                    normalize(dto.applicationCityId()), dto.habousCityId(), dto.habousArabicName());

            if (byApp.putIfAbsent(mapping.applicationCityId(), mapping) != null) {
                throw new IllegalStateException(
                        "Duplicate mapping for application city: " + mapping.applicationCityId());
            }
            // Two application cities legitimately may not share one ville: that would
            // make official data ambiguous. (Salé/Rabat is handled by mapping absence.)
            Mapping clash = byVille.putIfAbsent(mapping.habousCityId(), mapping);
            if (clash != null) {
                throw new IllegalStateException(
                        "Ville " + mapping.habousCityId() + " is mapped by both "
                                + clash.applicationCityId() + " and " + mapping.applicationCityId());
            }
            loaded.add(mapping);
        }

        if (loaded.size() != EXPECTED_MAPPING_COUNT) {
            throw new IllegalStateException(
                    "Expected " + EXPECTED_MAPPING_COUNT + " verified mappings in " + resourcePath
                            + " but found " + loaded.size());
        }

        this.mappings = List.copyOf(loaded);
        this.byApplicationCityId = Map.copyOf(byApp);
        this.byHabousCityId = Map.copyOf(byVille);
    }

    /** @return all verified mappings, in file order (immutable) */
    public List<Mapping> all() {
        return mappings;
    }

    /** @return the number of verified mappings (60) */
    public int count() {
        return mappings.size();
    }

    /**
     * @param applicationCityId an application city slug (case-insensitive)
     * @return the verified mapping, or empty if the city has no official data path
     */
    public Optional<Mapping> findByApplicationCityId(String applicationCityId) {
        if (applicationCityId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byApplicationCityId.get(normalize(applicationCityId)));
    }

    /**
     * @param habousCityId an official ville id
     * @return the mapping that targets it, or empty if no application city maps to it
     */
    public Optional<Mapping> findByHabousCityId(String habousCityId) {
        if (habousCityId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHabousCityId.get(habousCityId.trim()));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private MappingFile load(String resourcePath) {
        try (InputStream in = ApplicationHabousMapping.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Mapping resource not found: " + resourcePath);
            }
            MappingFile file = new ObjectMapper().readValue(in, MappingFile.class);
            if (file == null || file.mappings() == null || file.mappings().isEmpty()) {
                throw new IllegalStateException("No mappings in resource: " + resourcePath);
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read mapping resource: " + resourcePath, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MappingFile(String version, String parserVersion, List<MappingDto> mappings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MappingDto(
            String applicationCityId, String habousCityId, String habousArabicName, Boolean verified) {}
}
