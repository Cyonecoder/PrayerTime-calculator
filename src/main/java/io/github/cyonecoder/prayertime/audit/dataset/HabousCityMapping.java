package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The external application-city to Habous mapping, loaded from the bundled
 * {@code /research/habous-city-mapping.json} resource.
 *
 * <p>This is the <em>only</em> place the application-to-Habous relationship
 * lives; the production city model is not modified. Each entry carries the
 * candidate Habous {@code ville} identifier, the Arabic name, and a
 * {@code verified} flag (false until confirmed against a live fetch).
 */
public final class HabousCityMapping {

    private static final String DEFAULT_RESOURCE = "/research/habous-city-mapping.json";

    /**
     * One mapping entry.
     *
     * @param applicationCityId the application city slug
     * @param habousCityId      the external Habous {@code ville} identifier
     * @param habousArabicName  the Arabic city name
     * @param verified          whether the mapping has been confirmed live
     */
    public record Entry(
            String applicationCityId,
            String habousCityId,
            String habousArabicName,
            boolean verified) {
    }

    private final List<Entry> entries;
    private final Map<String, Entry> byAppId;
    private final String parserVersion;

    private HabousCityMapping(List<Entry> entries, String parserVersion) {
        this.entries = List.copyOf(entries);
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry e : entries) {
            index.put(normalize(e.applicationCityId()), e);
        }
        this.byAppId = Map.copyOf(index);
        this.parserVersion = parserVersion;
    }

    /** @return the mapping loaded from the bundled default resource. */
    public static HabousCityMapping load() {
        return load(DEFAULT_RESOURCE);
    }

    /**
     * @param resourcePath classpath resource path
     * @return the loaded mapping
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static HabousCityMapping load(String resourcePath) {
        try (InputStream in = HabousCityMapping.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Mapping resource not found: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                MappingFile file = new Gson().fromJson(reader, MappingFile.class);
                if (file == null || file.mappings == null || file.mappings.isEmpty()) {
                    throw new IllegalStateException("No mappings in resource: " + resourcePath);
                }
                for (Entry e : file.mappings) {
                    Objects.requireNonNull(e.applicationCityId(), "applicationCityId");
                    Objects.requireNonNull(e.habousCityId(), "habousCityId");
                    Objects.requireNonNull(e.habousArabicName(), "habousArabicName");
                }
                String parserVersion = file.parserVersion == null ? "unknown" : file.parserVersion;
                return new HabousCityMapping(file.mappings, parserVersion);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read mapping resource: " + resourcePath, e);
        }
    }

    /** @return all mapping entries, in file order. */
    public List<Entry> all() {
        return entries;
    }

    /**
     * @param applicationCityId an application city slug (case-insensitive)
     * @return the entry, if mapped
     */
    public Optional<Entry> forCity(String applicationCityId) {
        if (applicationCityId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAppId.get(normalize(applicationCityId)));
    }

    /** @return the application city slugs that are mapped, in file order. */
    public List<String> mappedCityIds() {
        return entries.stream().map(Entry::applicationCityId).toList();
    }

    /** @return the parser version recorded in the mapping file. */
    public String parserVersion() {
        return parserVersion;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class MappingFile {
        List<Entry> mappings;
        String parserVersion;
    }
}
