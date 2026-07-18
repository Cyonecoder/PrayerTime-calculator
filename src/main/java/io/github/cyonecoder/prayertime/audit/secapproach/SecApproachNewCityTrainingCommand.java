package io.github.cyonecoder.prayertime.audit.secapproach;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachCityTrainingRecord.CityTrainingRecord;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachCityTrainingRecord.DatasetInfo;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachCityTrainingRecord.SegmentMetrics;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachCityTrainingRecord.Status;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachSplitEvaluator.CitySplitEvaluation;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachSplitEvaluator.PrayerSplitEvaluation;
import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Handles {@code research train-new-cities}: trains every candidate city that has
 * no current sec-approach offsets, independently and per prayer, and writes a
 * per-city catalog with one of six statuses. Only {@link Status#ACCEPTED} cities
 * receive offsets; every other status carries none, so the provider keeps failing
 * closed and no city is ever given fabricated parameters.
 *
 * <pre>research train-new-cities --dataset &lt;dir&gt;
 *   [--low -900] [--high 900] [--suspicious-seconds 600] [--output file]</pre>
 */
public final class SecApproachNewCityTrainingCommand {

    private static final String INVENTORY = "/research/habous-candidate-inventory.json";
    private static final Path DEFAULT_VERIFICATION =
            Path.of("reports/secapproach-all-cities/habous-city-verification.json");
    private static final Path DEFAULT_OUTPUT =
            Path.of("reports/secapproach-all-cities/new-city-training.json");
    private static final Prayer[] ORDER = {
        Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA
    };
    private static final String[] KEYS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;

    public SecApproachNewCityTrainingCommand() {
        this(new MoroccoCityRegistry(), new HabousRawEngine());
    }

    public SecApproachNewCityTrainingCommand(MoroccoCityRegistry registry, HabousRawEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /** Runs the catalog training. */
    public void run(Map<String, String> flags, PrintStream out) {
        Path datasetDir = Path.of(require(flags, "dataset"));
        int low = intFlag(flags, "low", SecApproachOffsetSearch.DEFAULT_LOW);
        int high = intFlag(flags, "high", SecApproachOffsetSearch.DEFAULT_HIGH);
        int suspicious = intFlag(flags, "suspicious-seconds", 600);
        Path output = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_OUTPUT;
        Path verificationFile = flags.containsKey("verification")
                ? Path.of(flags.get("verification")) : DEFAULT_VERIFICATION;

        List<String> newCities = newTrainingCities();
        Map<String, String> verification = verificationStatuses(verificationFile);
        java.util.Set<String> verified = new java.util.HashSet<>(HabousCityMapping.load().verifiedCityIds());
        SecApproachTrainingDataset dataset =
                SecApproachTrainingDataset.fromDirectory(datasetDir, registry, engine);
        SecApproachSplitEvaluator splitEvaluator = new SecApproachSplitEvaluator(low, high);

        List<CityTrainingRecord> records = new ArrayList<>();
        for (String city : newCities) {
            records.add(train(city, verified, verification, dataset, splitEvaluator,
                    low, high, suspicious));
        }
        write(output, records, low, high, suspicious);

        Map<String, Integer> counts = new TreeMap<>();
        for (CityTrainingRecord r : records) {
            counts.merge(r.status(), 1, Integer::sum);
            out.printf(Locale.ROOT, "  %-16s %s%n", r.cityId(), r.status());
        }
        out.println("Status summary:");
        counts.forEach((s, n) -> out.printf(Locale.ROOT, "  %-24s %d%n", s, n));
        out.println("Wrote " + records.size() + " city training records -> " + output);
    }

    private CityTrainingRecord train(
            String city, java.util.Set<String> verified, Map<String, String> verification,
            SecApproachTrainingDataset dataset, SecApproachSplitEvaluator splitEvaluator,
            int low, int high, int suspicious) {

        // Not a verified Habous mapping: never trained. Distinguish "no page at all"
        // (mapping unverified) from "needs a human" per the live verification status.
        if (!verified.contains(city)) {
            String v = verification.getOrDefault(city, "NOT_LISTED_BY_HABOUS");
            Status status = "NOT_LISTED_BY_HABOUS".equals(v)
                    ? Status.MAPPING_UNVERIFIED : Status.MANUAL_REVIEW_REQUIRED;
            return notTrained(city, status);
        }

        List<TrainingObservation> probe = dataset.observations(city, Prayer.FAJR);
        int dayCount = probe.size();
        if (dayCount == 0 || dayCount * ORDER.length != 180) {
            return new CityTrainingRecord(city, datasetInfo(dataset, city, probe), null,
                    null, null, null, null, Status.INSUFFICIENT_DATA.name());
        }

        CitySplitEvaluation eval = splitEvaluator.evaluate(dataset, city);
        Map<Prayer, Integer> releaseOffsets = new EnumMap<>(Prayer.class);
        boolean boundary = false;
        int maxAbsOffset = 0;
        for (PrayerSplitEvaluation p : eval.prayers()) {
            releaseOffsets.put(p.prayer(), p.releaseOffset());
            boundary |= p.releaseOffset() == low || p.releaseOffset() == high;
            maxAbsOffset = Math.max(maxAbsOffset, Math.abs(p.releaseOffset()));
        }

        SegmentMetrics training = SegmentMetrics.aggregate(map(eval, PrayerSplitEvaluation::training));
        SegmentMetrics validation = SegmentMetrics.aggregate(map(eval, PrayerSplitEvaluation::validation));
        SegmentMetrics holdout = SegmentMetrics.aggregate(map(eval, PrayerSplitEvaluation::holdout));
        SegmentMetrics full = SegmentMetrics.aggregate(map(eval, PrayerSplitEvaluation::inSample));
        DatasetInfo ds = datasetInfo(dataset, city, probe);

        // A boundary touch or a many-minute offset is the signature of a wrong city
        // or coordinate — flagged for review, never silently accepted.
        if (boundary || maxAbsOffset > suspicious) {
            return new CityTrainingRecord(city, ds, null, training, validation, holdout, full,
                    Status.SUSPICIOUS_OFFSET.name());
        }
        // The per-city accuracy gates decide acceptance (same gates as the trainer),
        // including the out-of-sample holdout.
        SecApproachGates.Result gate = SecApproachGates.perCity(
                full.exactPercentage(), holdout.exactPercentage(),
                100.0 * full.withinOneCount() / full.observations(), full.maxAbsoluteError());
        if (gate.passed()) {
            return new CityTrainingRecord(city, ds, offsetsJson(releaseOffsets),
                    training, validation, holdout, full, Status.ACCEPTED.name());
        }
        return new CityTrainingRecord(city, ds, null, training, validation, holdout, full,
                Status.FAILED_ACCURACY_GATE.name());
    }

    private CityTrainingRecord notTrained(String city, Status status) {
        return new CityTrainingRecord(city,
                new DatasetInfo(null, null, 0, List.of()), null, null, null, null, null,
                status.name());
    }

    private DatasetInfo datasetInfo(
            SecApproachTrainingDataset dataset, String city, List<TrainingObservation> probe) {
        if (probe.isEmpty()) {
            return new DatasetInfo(null, null, 0, List.of());
        }
        List<java.time.LocalDate> dates =
                probe.stream().map(TrainingObservation::date).sorted().toList();
        String checksum = dataset.checksum(city);
        return new DatasetInfo(dates.get(0).toString(), dates.get(dates.size() - 1).toString(),
                probe.size() * ORDER.length,
                checksum.isBlank() ? List.of() : List.of(checksum));
    }

    private static List<SecApproachMetrics> map(
            CitySplitEvaluation eval,
            java.util.function.Function<PrayerSplitEvaluation, SecApproachMetrics> f) {
        return eval.prayers().stream().map(f).toList();
    }

    private static Map<String, Integer> offsetsJson(Map<Prayer, Integer> offsets) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < ORDER.length; i++) {
            out.put(KEYS[i], offsets.getOrDefault(ORDER[i], 0));
        }
        return out;
    }

    // --- IO -------------------------------------------------------------------

    private List<String> newTrainingCities() {
        JsonObject inv = readResource(INVENTORY);
        List<String> out = new ArrayList<>();
        for (JsonElement e : inv.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            if ("new-training".equals(c.get("secState").getAsString())) {
                out.add(c.get("canonicalSlug").getAsString());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static Map<String, String> verificationStatuses(Path file) {
        Map<String, String> out = new java.util.HashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonObject root = new Gson().fromJson(
                    Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            for (JsonElement e : root.getAsJsonArray("candidates")) {
                JsonObject c = e.getAsJsonObject();
                out.put(c.get("canonicalSlug").getAsString(), c.get("status").getAsString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
        return out;
    }

    private void write(
            Path file, List<CityTrainingRecord> records, int low, int high, int suspicious) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Independent per-city, per-prayer sec-approach training catalog for every "
                + "candidate without current offsets. Only ACCEPTED cities carry offsets; all other "
                + "statuses carry none (no fabricated parameters) and the provider fails closed.");
        root.put("caveat", "Holdout metrics are out-of-sample on a 30-day SUMMER window; "
                + "fullDatasetMetrics are IN-SAMPLE. Not an annual accuracy claim.");
        root.put("gates", "per-city: full-exact>=" + SecApproachGates.MIN_FULL_EXACT_PCT
                + "%, holdout-exact>=" + SecApproachGates.MIN_HOLDOUT_EXACT_PCT
                + "%, within-1min>=" + SecApproachGates.MIN_WITHIN_ONE_PCT
                + "%, max-abs<=" + SecApproachGates.MAX_ABS_ERROR_MIN + "min");
        root.put("searchLow", low);
        root.put("searchHigh", high);
        root.put("suspiciousOffsetSeconds", suspicious);
        Map<String, Integer> summary = new TreeMap<>();
        for (CityTrainingRecord r : records) {
            summary.merge(r.status(), 1, Integer::sum);
        }
        root.put("summary", summary);
        root.put("cities", records);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + file, e);
        }
    }

    private static JsonObject readResource(String resource) {
        try (InputStream in = SecApproachNewCityTrainingCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + resource);
            }
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }

    private static int intFlag(Map<String, String> flags, String key, int fallback) {
        String v = flags.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid --" + key + ": " + v);
        }
    }

    private static String require(Map<String, String> flags, String key) {
        String value = flags.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required flag --" + key);
        }
        return value;
    }
}
