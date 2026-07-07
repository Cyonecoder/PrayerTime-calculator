package io.github.cyonecoder.prayertime.audit.optimization;

import com.google.gson.Gson;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The reference observations (official Habous times) the search fits against,
 * loaded from a dataset directory of JSON files.
 *
 * <p>Each file is {@code { "days": [ { "city", "date", "fajr", ... } ] }}. The
 * loader is tolerant of multiple files and merges their days. Observations are
 * held in a stable, deterministic order (by city then date) so downstream
 * searches and reports are reproducible.
 */
public final class ReferenceDataset {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * One reference day for one city: the six official published times.
     *
     * @param city    the city slug
     * @param date    the civil date
     * @param fajr    official Fajr
     * @param sunrise official Sunrise (Chourouk)
     * @param dhuhr   official Dhuhr
     * @param asr     official Asr
     * @param maghrib official Maghrib
     * @param isha    official Isha
     */
    public record Observation(
            String city, LocalDate date,
            LocalTime fajr, LocalTime sunrise, LocalTime dhuhr,
            LocalTime asr, LocalTime maghrib, LocalTime isha) {

        /** @return the official published time for the given prayer. */
        public LocalTime timeFor(Prayer prayer) {
            return switch (prayer) {
                case FAJR -> fajr;
                case SUNRISE -> sunrise;
                case DHUHR -> dhuhr;
                case ASR -> asr;
                case MAGHRIB -> maghrib;
                case ISHA -> isha;
            };
        }

        /** @return the official published minute-of-day for the given prayer. */
        public int minuteOfDayFor(Prayer prayer) {
            LocalTime t = timeFor(prayer);
            return t.getHour() * 60 + t.getMinute();
        }
    }

    private final List<Observation> observations;

    private ReferenceDataset(List<Observation> observations) {
        this.observations = List.copyOf(observations);
    }

    /**
     * Loads and merges every {@code *.json} file in the given directory.
     *
     * @param directory the dataset directory
     * @return the loaded dataset
     * @throws IllegalArgumentException if the directory is missing or has no data
     */
    public static ReferenceDataset load(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Dataset directory not found: " + directory);
        }
        Gson gson = new Gson();
        List<Observation> merged = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            for (Path file : jsonFiles) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    DatasetFile parsed = gson.fromJson(reader, DatasetFile.class);
                    if (parsed == null || parsed.days == null) {
                        continue;
                    }
                    for (DayDto d : parsed.days) {
                        merged.add(toObservation(d, file));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read dataset directory: " + directory, e);
        }
        if (merged.isEmpty()) {
            throw new IllegalArgumentException("Dataset has no observations: " + directory);
        }
        merged.sort(Comparator.comparing(Observation::city).thenComparing(Observation::date));
        return new ReferenceDataset(merged);
    }

    /** @return a dataset built directly from observations (for tests). */
    public static ReferenceDataset of(List<Observation> observations) {
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("observations must not be empty");
        }
        List<Observation> copy = new ArrayList<>(observations);
        copy.sort(Comparator.comparing(Observation::city).thenComparing(Observation::date));
        return new ReferenceDataset(copy);
    }

    private static Observation toObservation(DayDto d, Path file) {
        try {
            return new Observation(
                    d.city.trim().toLowerCase(java.util.Locale.ROOT),
                    LocalDate.parse(d.date),
                    LocalTime.parse(d.fajr, HM),
                    LocalTime.parse(d.sunrise, HM),
                    LocalTime.parse(d.dhuhr, HM),
                    LocalTime.parse(d.asr, HM),
                    LocalTime.parse(d.maghrib, HM),
                    LocalTime.parse(d.isha, HM));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Malformed reference day in " + file + ": " + e.getMessage(), e);
        }
    }

    /** @return all observations, ordered by city then date (immutable). */
    public List<Observation> observations() {
        return observations;
    }

    /**
     * The observations restricted to the given cities and date range.
     *
     * @param cities   city slugs to keep; empty keeps all cities
     * @param from     inclusive lower date bound (nullable to leave open)
     * @param to       inclusive upper date bound (nullable to leave open)
     * @return a filtered dataset (never empty)
     * @throws IllegalArgumentException if nothing matches the filter
     */
    public ReferenceDataset filter(Set<String> cities, LocalDate from, LocalDate to) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String c : cities) {
            wanted.add(c.trim().toLowerCase(java.util.Locale.ROOT));
        }
        List<Observation> kept = observations.stream()
                .filter(o -> wanted.isEmpty() || wanted.contains(o.city()))
                .filter(o -> from == null || !o.date().isBefore(from))
                .filter(o -> to == null || !o.date().isAfter(to))
                .toList();
        if (kept.isEmpty()) {
            throw new IllegalArgumentException(
                    "No reference observations match cities=" + cities
                            + " from=" + from + " to=" + to);
        }
        return new ReferenceDataset(kept);
    }

    /** @return the distinct city slugs present, in sorted order. */
    public List<String> cities() {
        return observations.stream().map(Observation::city).distinct().sorted().toList();
    }

    /** @return the distinct dates present, in sorted order. */
    public List<LocalDate> dates() {
        return observations.stream().map(Observation::date).distinct().sorted().toList();
    }

    // --- Gson binding types ---------------------------------------------------

    private static final class DatasetFile {
        List<DayDto> days;
    }

    private static final class DayDto {
        String city;
        String date;
        String fajr;
        String sunrise;
        String dhuhr;
        String asr;
        String maghrib;
        String isha;
    }
}
