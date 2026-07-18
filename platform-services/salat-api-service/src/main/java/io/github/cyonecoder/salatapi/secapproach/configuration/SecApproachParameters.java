package io.github.cyonecoder.salatapi.secapproach.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Group 8.4 — the released sec-approach v2 parameters: the per-city, per-prayer
 * constant seconds offsets, loaded from {@code /secapproach/morocco-habous-secaproach.json}.
 *
 * <p>Validated at startup against the released shape. A different parameter
 * version or city count is <strong>never silently accepted</strong>: construction
 * fails and the application does not start. This is what stops the v1 file
 * ({@code morocco-habous-secaproach-base14.json}, 14 offsets) or a partially
 * retrained file from being served as if it were v2.
 *
 * <p>Ported from {@code SecondsApproachPrayerTimesProvider}'s parameter loading
 * with Gson replaced by Jackson, and the resource repointed from
 * {@code /research/} to {@code /secapproach/}; see {@code docs/EXTRACTION_MAP.md}.
 */
@Component
public class SecApproachParameters {

    /** The only accepted parameter version. */
    public static final String EXPECTED_PARAMETER_VERSION = "morocco-habous-secaproach-v2";
    /** {@code generatedFrom.verifiedCityCount} — the verified mappings the training drew from. */
    public static final int EXPECTED_VERIFIED_CITY_COUNT = 60;
    /** {@code generatedFrom.trainedCityCount} — the cities that accepted offsets. */
    public static final int EXPECTED_TRAINED_CITY_COUNT = 48;
    /** The number of offset objects that must be present. */
    public static final int EXPECTED_OFFSET_COUNT = 48;

    private static final String DEFAULT_RESOURCE = "/secapproach/morocco-habous-secaproach.json";

    private final String parameterVersion;
    private final String datasetChecksum;
    private final String model;
    private final String validatedWindowStart;
    private final String validatedWindowEnd;
    private final Map<String, Map<Prayer, Integer>> offsets;

    public SecApproachParameters() {
        this(DEFAULT_RESOURCE);
    }

    /**
     * The Spring-wired constructor: loads the configured resource, then cross-checks
     * the loaded parameters against the <em>configured</em> expectations too — so a
     * misconfigured {@code salat.sec-approach.expected-*} fails startup rather than
     * quietly accepting an unexpected resource (spec 7 and 26).
     *
     * @param properties the sec-approach configuration
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SecApproachParameters(io.github.cyonecoder.salatapi.config.SecApproachProperties properties) {
        this(properties.parameterClasspath());
        if (!parameterVersion.equals(properties.expectedParameterVersion())) {
            throw new IllegalStateException("Refusing to start: configured expected version '"
                    + properties.expectedParameterVersion() + "' does not match the loaded '"
                    + parameterVersion + "'");
        }
        if (offsets.size() != properties.expectedSupportedCityCount()) {
            throw new IllegalStateException("Refusing to start: configured expected city count "
                    + properties.expectedSupportedCityCount() + " does not match the loaded "
                    + offsets.size());
        }
    }

    /**
     * @param resourcePath absolute classpath resource path
     * @throws IllegalStateException if the resource is missing, is not the released
     *                               v2 parameter set, or has unexpected counts
     */
    SecApproachParameters(String resourcePath) {
        ParametersFile file = load(resourcePath);

        if (!EXPECTED_PARAMETER_VERSION.equals(file.parameterVersion())) {
            throw new IllegalStateException(
                    "Refusing to start: expected parameterVersion '" + EXPECTED_PARAMETER_VERSION
                            + "' but " + resourcePath + " declares '" + file.parameterVersion()
                            + "'. The v1 base14 parameter file must not be used.");
        }
        GeneratedFrom generatedFrom = file.generatedFrom();
        if (generatedFrom == null) {
            throw new IllegalStateException("Refusing to start: " + resourcePath + " has no generatedFrom block");
        }
        requireCount("generatedFrom.verifiedCityCount", generatedFrom.verifiedCityCount(), EXPECTED_VERIFIED_CITY_COUNT, resourcePath);
        requireCount("generatedFrom.trainedCityCount", generatedFrom.trainedCityCount(), EXPECTED_TRAINED_CITY_COUNT, resourcePath);
        requireCount("offsets", file.offsets().size(), EXPECTED_OFFSET_COUNT, resourcePath);

        Map<String, Map<Prayer, Integer>> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : file.offsets().entrySet()) {
            String slug = entry.getKey().trim().toLowerCase(Locale.ROOT);
            Map<Prayer, Integer> perPrayer = new EnumMap<>(Prayer.class);
            for (Map.Entry<String, Integer> offset : entry.getValue().entrySet()) {
                Prayer prayer = Prayer.fromKey(offset.getKey()).orElseThrow(() ->
                        new IllegalStateException(
                                "Unknown prayer '" + offset.getKey() + "' in offsets for " + slug));
                perPrayer.put(prayer, offset.getValue());
            }
            // Every accepted city carries all six prayers; a partial entry would
            // silently fall back to the raw time for the missing prayer.
            if (perPrayer.size() != Prayer.values().length) {
                throw new IllegalStateException(
                        "City " + slug + " has " + perPrayer.size()
                                + " prayer offsets but all " + Prayer.values().length + " are required");
            }
            parsed.put(slug, Map.copyOf(perPrayer));
        }

        this.parameterVersion = file.parameterVersion();
        this.datasetChecksum = generatedFrom.datasetChecksum();
        this.model = file.model();
        this.validatedWindowStart = generatedFrom.firstDate();
        this.validatedWindowEnd = generatedFrom.lastDate();
        this.offsets = Map.copyOf(parsed);
    }

    /** @return the validated parameter version ({@code morocco-habous-secaproach-v2}) */
    public String parameterVersion() {
        return parameterVersion;
    }

    /** @return the published formula, e.g. {@code publishedMinute = floor((rawSeconds + offset)/60)} */
    public String model() {
        return model;
    }

    /** @return the first date of the validation window ({@code 2026-06-17}) */
    public String validatedWindowStart() {
        return validatedWindowStart;
    }

    /** @return the last date of the validation window ({@code 2026-07-16}) */
    public String validatedWindowEnd() {
        return validatedWindowEnd;
    }

    /** @return the SHA-256 of the training dataset the offsets were derived from */
    public String datasetChecksum() {
        return datasetChecksum;
    }

    /** @return the 48 city slugs that have accepted offsets (immutable) */
    public Set<String> supportedCitySlugs() {
        return offsets.keySet();
    }

    /** @return the number of cities with accepted offsets (48) */
    public int count() {
        return offsets.size();
    }

    /** @return {@code true} if the city has accepted six-prayer offsets */
    public boolean isSupported(String citySlug) {
        return citySlug != null && offsets.containsKey(citySlug.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * @param citySlug an application city slug
     * @return the six per-prayer offsets, or empty if the city has none. The
     *         caller must never substitute zero offsets for an absent city: an
     *         unsupported city is refused, not guessed.
     */
    public Optional<Map<Prayer, Integer>> offsetsFor(String citySlug) {
        if (citySlug == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(offsets.get(citySlug.trim().toLowerCase(Locale.ROOT)));
    }

    private static void requireCount(String field, int actual, int expected, String resourcePath) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Refusing to start: expected " + field + " = " + expected + " in " + resourcePath
                            + " but found " + actual);
        }
    }

    private ParametersFile load(String resourcePath) {
        try (InputStream in = SecApproachParameters.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Sec-approach parameter resource not found: " + resourcePath);
            }
            ParametersFile file = new ObjectMapper().readValue(in, ParametersFile.class);
            if (file == null || file.offsets() == null || file.offsets().isEmpty()) {
                throw new IllegalStateException("No offsets in resource: " + resourcePath);
            }
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read sec-approach parameters: " + resourcePath, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ParametersFile(
            String parameterVersion,
            String model,
            String generatorVersion,
            GeneratedFrom generatedFrom,
            Map<String, Map<String, Integer>> offsets) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeneratedFrom(
            String firstDate,
            String lastDate,
            String datasetChecksum,
            int verifiedCityCount,
            int trainedCityCount) {}
}
