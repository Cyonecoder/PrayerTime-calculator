package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Outcome;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Record;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Status;
import io.github.cyonecoder.prayertime.audit.dataset.HabousSnapshotStore.StoredSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Generates the dataset coverage report into {@code build/reports/habous-dataset/}:
 * {@code coverage.json}, {@code coverage.md}, {@code missing-cities.csv},
 * {@code missing-months.csv}, {@code invalid-pages.csv},
 * {@code duplicate-snapshots.csv} and {@code checksum-history.csv}.
 *
 * <p>The report merges what is actually on disk (scanned snapshots and their
 * checksums) with the fixed split, the external mapping, and — when a fetch just
 * ran — the collection outcome (invalid pages, duplicates, source changes).
 */
public final class HabousDatasetCoverageReport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int PRAYERS_PER_DAY = 6;

    private final HabousCityMapping mapping;
    private final HabousDatasetSplit split;
    private final HabousSnapshotStore store;

    public HabousDatasetCoverageReport(
            HabousCityMapping mapping, HabousDatasetSplit split, HabousSnapshotStore store) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.split = Objects.requireNonNull(split, "split");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Generates all seven report files.
     *
     * @param outputDir where to write the report
     * @param year      the year in scope
     * @param months    the months requested (1..12)
     * @param outcome   the just-completed collection outcome, if any
     * @return the output directory
     */
    public Path generate(Path outputDir, int year, List<Integer> months, Optional<Outcome> outcome) {
        Objects.requireNonNull(outputDir, "outputDir");
        List<StoredSnapshot> snapshots = store.scanAll();

        // Snapshots grouped by application city (primaries only for coverage counts).
        Map<String, List<StoredSnapshot>> primariesByCity = new LinkedHashMap<>();
        for (StoredSnapshot s : snapshots) {
            if (!HabousSnapshotStore.isRevision(s.directory())) {
                primariesByCity.computeIfAbsent(s.month().applicationCityId(), k -> new ArrayList<>()).add(s);
            }
        }

        TreeSet<String> universe = new TreeSet<>();
        universe.addAll(mapping.mappedCityIds());
        universe.addAll(split.allCities());

        List<Record> records = outcome.map(Outcome::records).orElse(List.of());

        try {
            Files.createDirectories(outputDir);
            Map<String, Object> summary = writeCoverageJson(
                    outputDir.resolve("coverage.json"), universe, primariesByCity, snapshots, records, year, months);
            writeCoverageMd(outputDir.resolve("coverage.md"), summary, universe, primariesByCity);
            writeMissingCities(outputDir.resolve("missing-cities.csv"), universe, primariesByCity);
            writeMissingMonths(outputDir.resolve("missing-months.csv"), primariesByCity, year, months);
            writeInvalidPages(outputDir.resolve("invalid-pages.csv"), records);
            writeDuplicateSnapshots(outputDir.resolve("duplicate-snapshots.csv"), snapshots, records);
            writeChecksumHistory(outputDir.resolve("checksum-history.csv"), snapshots, records);
            return outputDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write coverage report to " + outputDir, e);
        }
    }

    private Map<String, Object> writeCoverageJson(
            Path path, TreeSet<String> universe, Map<String, List<StoredSnapshot>> primariesByCity,
            List<StoredSnapshot> snapshots, List<Record> records, int year, List<Integer> months)
            throws IOException {

        int downloadedCities = primariesByCity.size();
        int availableMonths = primariesByCity.values().stream().mapToInt(List::size).sum();
        int totalDays = primariesByCity.values().stream()
                .flatMap(List::stream).mapToInt(s -> s.month().dailyRecords().size()).sum();
        int missingDates = primariesByCity.values().stream()
                .flatMap(List::stream).mapToInt(s -> s.month().missingDays().size()).sum();
        long revisions = snapshots.stream().filter(s -> HabousSnapshotStore.isRevision(s.directory())).count();
        long duplicateRuns = records.stream().filter(r -> r.status() == Status.DUPLICATE).count();
        long invalidPages = records.stream().filter(r -> r.status().isInvalid()).count();
        long sourceChanges = revisions + records.stream()
                .filter(r -> r.status() == Status.REVISION_SAVED).count();

        List<Map<String, Object>> perCity = new ArrayList<>();
        for (String city : universe) {
            List<StoredSnapshot> primaries = primariesByCity.getOrDefault(city, List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("city", city);
            row.put("split", split.splitFor(city).map(Enum::name).orElse("UNASSIGNED"));
            Optional<HabousCityMapping.Entry> mapped = mapping.forCity(city);
            row.put("mapped", mapped.isPresent());
            row.put("verified", mapped.map(HabousCityMapping.Entry::verified).orElse(false));
            row.put("monthsDownloaded", primaries.size());
            row.put("prayerObservations",
                    primaries.stream().mapToInt(s -> s.month().dailyRecords().size()).sum() * PRAYERS_PER_DAY);
            perCity.add(row);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("year", year);
        summary.put("monthsRequested", months);
        summary.put("mappedCities", mapping.mappedCityIds().size());
        summary.put("splitCities", split.allCities().size());
        summary.put("downloadedCities", downloadedCities);
        summary.put("availableMonths", availableMonths);
        summary.put("missingDates", missingDates);
        summary.put("duplicateRows", duplicateRuns + revisions);
        summary.put("malformedOrInvalidPages", invalidPages);
        summary.put("sourceChanges", sourceChanges);
        summary.put("totalPrayerObservations", totalDays * PRAYERS_PER_DAY);
        summary.put("perCity", perCity);

        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(summary, w);
            w.write("\n");
        }
        return summary;
    }

    private void writeCoverageMd(
            Path path, Map<String, Object> summary, TreeSet<String> universe,
            Map<String, List<StoredSnapshot>> primariesByCity) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("# Habous dataset coverage\n\n");
            w.write("| metric | value |\n|---|---|\n");
            for (Map.Entry<String, Object> e : summary.entrySet()) {
                if (e.getKey().equals("perCity") || e.getKey().equals("monthsRequested")) {
                    continue;
                }
                w.write("| " + e.getKey() + " | " + e.getValue() + " |\n");
            }
            w.write("\n## Per city\n\n");
            w.write("| city | split | mapped | verified | months | observations |\n");
            w.write("|---|---|---|---|---|---|\n");
            for (String city : universe) {
                List<StoredSnapshot> primaries = primariesByCity.getOrDefault(city, List.of());
                int obs = primaries.stream()
                        .mapToInt(s -> s.month().dailyRecords().size()).sum() * PRAYERS_PER_DAY;
                w.write(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %d |%n",
                        city, split.splitFor(city).map(Enum::name).orElse("UNASSIGNED"),
                        mapping.forCity(city).isPresent(),
                        mapping.forCity(city).map(HabousCityMapping.Entry::verified).orElse(false),
                        primaries.size(), obs));
            }
        }
    }

    private void writeMissingCities(
            Path path, TreeSet<String> universe, Map<String, List<StoredSnapshot>> primariesByCity)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("city,split,mapped,verified,reason\n");
            for (String city : universe) {
                if (primariesByCity.containsKey(city)) {
                    continue;
                }
                Optional<HabousCityMapping.Entry> mapped = mapping.forCity(city);
                String reason = mapped.isEmpty()
                        ? "no application-to-Habous mapping"
                        : "mapped but no snapshot downloaded";
                w.write(String.format(Locale.ROOT, "%s,%s,%s,%s,%s%n",
                        city, split.splitFor(city).map(Enum::name).orElse("UNASSIGNED"),
                        mapped.isPresent(), mapped.map(HabousCityMapping.Entry::verified).orElse(false),
                        reason));
            }
        }
    }

    private void writeMissingMonths(
            Path path, Map<String, List<StoredSnapshot>> primariesByCity, int year, List<Integer> months)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("city,year,month,reason\n");
            for (String city : mapping.mappedCityIds()) {
                TreeSet<Integer> present = new TreeSet<>();
                for (StoredSnapshot s : primariesByCity.getOrDefault(city, List.of())) {
                    if (s.month().year() == year) {
                        present.add(s.month().month());
                    }
                }
                for (int month : months) {
                    if (!present.contains(month)) {
                        w.write(String.format(Locale.ROOT, "%s,%d,%02d,not downloaded%n", city, year, month));
                    }
                }
            }
        }
    }

    private void writeInvalidPages(Path path, List<Record> records) throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("city,year,month,status,message\n");
            for (Record r : records) {
                if (r.status().isInvalid()) {
                    w.write(String.format(Locale.ROOT, "%s,%d,%02d,%s,%s%n",
                            r.applicationCityId(), r.year(), r.month(), r.status(), csv(r.message())));
                }
            }
        }
    }

    private void writeDuplicateSnapshots(Path path, List<StoredSnapshot> snapshots, List<Record> records)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("city,year,month,kind,checksum,location\n");
            for (StoredSnapshot s : snapshots) {
                if (HabousSnapshotStore.isRevision(s.directory())) {
                    w.write(String.format(Locale.ROOT, "%s,%d,%02d,revision,%s,%s%n",
                            s.month().applicationCityId(), s.month().year(), s.month().month(),
                            s.checksum(), s.directory()));
                }
            }
            for (Record r : records) {
                if (r.status() == Status.DUPLICATE) {
                    w.write(String.format(Locale.ROOT, "%s,%d,%02d,duplicate-request,%s,%s%n",
                            r.applicationCityId(), r.year(), r.month(), csv(r.checksum()), csv(r.message())));
                }
            }
        }
    }

    private void writeChecksumHistory(Path path, List<StoredSnapshot> snapshots, List<Record> records)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("city,year,month,checksum,fetchedAt,kind,dayCount\n");
            for (StoredSnapshot s : snapshots) {
                HabousReferenceMonth m = s.month();
                w.write(String.format(Locale.ROOT, "%s,%d,%02d,%s,%s,%s,%d%n",
                        m.applicationCityId(), m.year(), m.month(), s.checksum(), m.fetchedAt(),
                        HabousSnapshotStore.isRevision(s.directory()) ? "revision" : "primary",
                        m.dailyRecords().size()));
            }
            for (Record r : records) {
                if (r.status() == Status.REVISION_SAVED) {
                    w.write(String.format(Locale.ROOT, "%s,%d,%02d,%s,,run-revision,%d%n",
                            r.applicationCityId(), r.year(), r.month(), csv(r.checksum()), r.dayCount()));
                }
            }
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(",") ? "\"" + escaped + "\"" : escaped;
    }
}
