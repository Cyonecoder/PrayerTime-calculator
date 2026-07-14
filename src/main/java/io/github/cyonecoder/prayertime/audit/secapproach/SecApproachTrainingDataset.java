package io.github.cyonecoder.prayertime.audit.secapproach;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.audit.dataset.HabousDatasetSplit;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The sec-approach training input: for every (city, prayer, date) it pairs the
 * <em>raw reconstructed astronomical seconds</em> with the official Habous minute,
 * plus the dataset split and the source snapshot checksum.
 *
 * <p>The raw seconds come from the exact production pipeline — {@link
 * HabousRawEngine} over {@link ReconstructedMethod#frozen()} at the city
 * coordinates, {@code Africa/Casablanca}, and the date — rounded to the second
 * exactly as {@code SecondsApproachPrayerTimesProvider} does. The already-rounded
 * astronomical minute is never used.
 */
public final class SecApproachTrainingDataset {

    /**
     * One training observation.
     *
     * @param city             application city slug
     * @param date             the civil date
     * @param prayer           the prayer
     * @param rawSeconds       raw reconstructed second-of-day (rounded to the second)
     * @param officialMinute   official Habous minute-of-day
     * @param split            dataset split (TRAINING/VALIDATION/HOLDOUT/UNASSIGNED)
     * @param snapshotChecksum SHA-256 of the source snapshot the official value came from
     */
    public record TrainingObservation(
            String city,
            LocalDate date,
            Prayer prayer,
            long rawSeconds,
            int officialMinute,
            String split,
            String snapshotChecksum) {
    }

    private final Map<String, Map<Prayer, List<TrainingObservation>>> byCityPrayer;

    private SecApproachTrainingDataset(
            Map<String, Map<Prayer, List<TrainingObservation>>> byCityPrayer) {
        this.byCityPrayer = byCityPrayer;
    }

    /**
     * Builds the training dataset from a research snapshot directory.
     *
     * @param datasetDir the {@code research-data/habous} directory
     * @param registry   the city model (for coordinates/zone)
     * @param engine     the raw astronomical engine
     * @return the training dataset
     */
    public static SecApproachTrainingDataset fromDirectory(
            Path datasetDir, MoroccoCityRegistry registry, HabousRawEngine engine) {
        return fromDirectory(datasetDir, registry, engine, java.util.Set.of(), null, null);
    }

    /**
     * Builds the training dataset from a directory, restricted to the given cities
     * and/or date range.
     *
     * @param datasetDir the {@code research-data/habous} directory
     * @param registry   the city model
     * @param engine     the raw astronomical engine
     * @param cities     city slugs to keep (empty keeps all)
     * @param from       inclusive lower date bound (nullable)
     * @param to         inclusive upper date bound (nullable)
     * @return the filtered training dataset
     */
    public static SecApproachTrainingDataset fromDirectory(
            Path datasetDir, MoroccoCityRegistry registry, HabousRawEngine engine,
            java.util.Set<String> cities, LocalDate from, LocalDate to) {
        ReferenceDataset dataset = ReferenceDataset.load(datasetDir);
        if (!cities.isEmpty() || from != null || to != null) {
            dataset = dataset.filter(cities, from, to);
        }
        return from(dataset, checksumsByCity(datasetDir),
                HabousDatasetSplit.load(), registry, engine, ReconstructedMethod.frozen());
    }

    /**
     * Builds the training dataset from already-loaded pieces (used by tests).
     *
     * @param dataset   the official reference observations
     * @param checksums city slug to source snapshot checksum
     * @param split     the fixed dataset split
     * @param registry  the city model
     * @param engine    the raw astronomical engine
     * @param method    the frozen reconstructed method
     * @return the training dataset
     */
    public static SecApproachTrainingDataset from(
            ReferenceDataset dataset, Map<String, String> checksums, HabousDatasetSplit split,
            MoroccoCityRegistry registry, HabousRawEngine engine,
            io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate method) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(method, "method");
        Map<String, Map<Prayer, List<TrainingObservation>>> out = new TreeMap<>();
        for (Observation o : dataset.observations()) {
            Optional<City> resolved = registry.findById(o.city()).or(() -> registry.findByNameOrAlias(o.city()));
            if (resolved.isEmpty()) {
                continue; // a snapshot with no city model entry is skipped, not guessed
            }
            City city = resolved.get();
            String splitLabel = split.splitFor(o.city()).map(Enum::name).orElse("UNASSIGNED");
            String checksum = checksums.getOrDefault(o.city(), "");
            Map<Prayer, List<TrainingObservation>> byPrayer =
                    out.computeIfAbsent(o.city(), k -> new EnumMap<>(Prayer.class));
            for (Prayer prayer : Prayer.values()) {
                long raw = Math.round(
                        engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), o.date()));
                byPrayer.computeIfAbsent(prayer, k -> new ArrayList<>()).add(new TrainingObservation(
                        o.city(), o.date(), prayer, raw, o.minuteOfDayFor(prayer),
                        splitLabel, checksum));
            }
        }
        return new SecApproachTrainingDataset(out);
    }

    /** @return the city slugs present, ascending. */
    public List<String> cities() {
        return List.copyOf(byCityPrayer.keySet());
    }

    /**
     * @param city   the city slug
     * @param prayer the prayer
     * @return that (city, prayer)'s observations (empty if none)
     */
    public List<TrainingObservation> observations(String city, Prayer prayer) {
        Map<Prayer, List<TrainingObservation>> byPrayer = byCityPrayer.get(city);
        if (byPrayer == null) {
            return List.of();
        }
        return byPrayer.getOrDefault(prayer, List.of());
    }

    /** @return the snapshot checksum recorded for a city (or empty string). */
    public String checksum(String city) {
        List<TrainingObservation> any = observations(city, Prayer.FAJR);
        return any.isEmpty() ? "" : any.get(0).snapshotChecksum();
    }

    /** @return the earliest date across all cities, or empty if the dataset is empty. */
    public java.util.Optional<LocalDate> firstDate() {
        return allDates().stream().min(LocalDate::compareTo);
    }

    /** @return the latest date across all cities, or empty if the dataset is empty. */
    public java.util.Optional<LocalDate> lastDate() {
        return allDates().stream().max(LocalDate::compareTo);
    }

    /**
     * A deterministic SHA-256 over the per-city source snapshot checksums, so the
     * generated parameters can record exactly which reviewed data produced them.
     *
     * @return the aggregate dataset checksum (hex)
     */
    public String datasetChecksum() {
        StringBuilder sb = new StringBuilder();
        for (String city : cities()) {
            sb.append(city).append('=').append(checksum(city)).append('\n');
        }
        return io.github.cyonecoder.prayertime.audit.dataset.HabousSnapshotStore.sha256Hex(sb.toString());
    }

    private List<LocalDate> allDates() {
        List<LocalDate> dates = new ArrayList<>();
        for (String city : cities()) {
            for (TrainingObservation o : observations(city, Prayer.FAJR)) {
                dates.add(o.date());
            }
        }
        return dates;
    }

    /** Loads the source SHA-256 per city from the snapshot files in {@code dir}. */
    private static Map<String, String> checksumsByCity(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                JsonObject o = new Gson().fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("city") && o.has("sourceSha256")) {
                    out.put(o.get("city").getAsString().trim().toLowerCase(Locale.ROOT),
                            o.get("sourceSha256").getAsString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading snapshot checksums from " + dir, e);
        }
        return out;
    }
}
