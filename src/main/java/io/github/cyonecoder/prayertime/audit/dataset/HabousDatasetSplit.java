package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The fixed research train/validation/holdout partition, loaded from the bundled
 * {@code /research/habous-dataset-split.json} resource.
 *
 * <p>The split is frozen: it must not change once parameter fitting has begun,
 * so validation and holdout results remain honest. Cities are identified by
 * application slug.
 */
public final class HabousDatasetSplit {

    private static final String DEFAULT_RESOURCE = "/research/habous-dataset-split.json";

    /** The three partitions of the research split. */
    public enum Split {
        TRAINING,
        VALIDATION,
        HOLDOUT
    }

    private final Set<String> training;
    private final Set<String> validation;
    private final Set<String> holdout;

    private HabousDatasetSplit(Set<String> training, Set<String> validation, Set<String> holdout) {
        this.training = normalizedCopy(training);
        this.validation = normalizedCopy(validation);
        this.holdout = normalizedCopy(holdout);
        assertDisjoint();
    }

    /** @return the split loaded from the bundled default resource. */
    public static HabousDatasetSplit load() {
        return load(DEFAULT_RESOURCE);
    }

    /**
     * @param resourcePath classpath resource path
     * @return the loaded split
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static HabousDatasetSplit load(String resourcePath) {
        try (InputStream in = HabousDatasetSplit.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Split resource not found: " + resourcePath);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                SplitFile file = new Gson().fromJson(reader, SplitFile.class);
                if (file == null || file.training == null || file.validation == null
                        || file.holdout == null) {
                    throw new IllegalStateException("Malformed split resource: " + resourcePath);
                }
                return new HabousDatasetSplit(
                        new LinkedHashSet<>(file.training),
                        new LinkedHashSet<>(file.validation),
                        new LinkedHashSet<>(file.holdout));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read split resource: " + resourcePath, e);
        }
    }

    public Set<String> training() {
        return training;
    }

    public Set<String> validation() {
        return validation;
    }

    public Set<String> holdout() {
        return holdout;
    }

    /** @return every city slug across all three partitions. */
    public Set<String> allCities() {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(training);
        all.addAll(validation);
        all.addAll(holdout);
        return Set.copyOf(all);
    }

    /**
     * @param applicationCityId a city slug (case-insensitive)
     * @return the partition the city belongs to, if any
     */
    public Optional<Split> splitFor(String applicationCityId) {
        if (applicationCityId == null) {
            return Optional.empty();
        }
        String slug = applicationCityId.trim().toLowerCase(Locale.ROOT);
        if (training.contains(slug)) {
            return Optional.of(Split.TRAINING);
        }
        if (validation.contains(slug)) {
            return Optional.of(Split.VALIDATION);
        }
        if (holdout.contains(slug)) {
            return Optional.of(Split.HOLDOUT);
        }
        return Optional.empty();
    }

    private void assertDisjoint() {
        for (String city : training) {
            if (validation.contains(city) || holdout.contains(city)) {
                throw new IllegalStateException("City appears in multiple splits: " + city);
            }
        }
        for (String city : validation) {
            if (holdout.contains(city)) {
                throw new IllegalStateException("City appears in multiple splits: " + city);
            }
        }
    }

    private static Set<String> normalizedCopy(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            out.add(Objects.requireNonNull(s, "city").trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    private static final class SplitFile {
        List<String> training;
        List<String> validation;
        List<String> holdout;
    }
}
