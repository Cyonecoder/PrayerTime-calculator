package io.github.cyonecoder.prayertime.audit.optimization;

import io.github.cyonecoder.prayertime.audit.optimization.SecondsApproachOffsetFitter.CityFit;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles {@code research fit-secaproach}: fits the per-city, per-prayer
 * sec-approach seconds offsets from a reference dataset and writes the
 * {@code morocco-habous-secaproach.json} parameter file.
 *
 * <p>Only cities whose in-sample exact-minute agreement meets {@code --min-exact}
 * (default {@value #DEFAULT_MIN_EXACT}) are written; cities below the band, or not
 * present in the city registry, are reported and skipped. The offsets are constant
 * calculation parameters — never per-date values — so the output stays a formula,
 * not a memorised timetable.
 *
 * <p>Pass {@code --base <file>} to preserve already-shipped cities: any city
 * present in the base file keeps its committed offsets verbatim (its production
 * output is never silently changed), and only cities new to the base are fitted
 * and appended. Without {@code --base} the whole file is regenerated from the
 * dataset.
 *
 * <pre>research fit-secaproach --dataset &lt;dir&gt; [--cities a,b] [--output file]
 *   [--min-exact 0.95] [--base file]</pre>
 */
public final class SecondsApproachFitCommand {

    /** Default minimum in-sample exact ratio for a city to be written. */
    public static final double DEFAULT_MIN_EXACT = 0.95;

    private static final Path DEFAULT_OUTPUT =
            Path.of("build/reports/habous-secaproach/morocco-habous-secaproach.json");

    private final MoroccoCityRegistry registry;
    private final HabousRawEngine engine;

    public SecondsApproachFitCommand() {
        this(new MoroccoCityRegistry(), new HabousRawEngine());
    }

    public SecondsApproachFitCommand(MoroccoCityRegistry registry, HabousRawEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    /**
     * The result of a fit run (for tests and callers), independent of I/O.
     *
     * @param written  cities written (met the band), sorted by slug
     * @param rejected cities skipped for being below the band, with their ratio
     * @param unresolved dataset cities absent from the registry
     */
    public record Outcome(
            List<CityFit> written,
            List<Rejected> rejected,
            List<String> unresolved) {

        /** A city excluded for missing the accuracy band. */
        public record Rejected(String cityId, double exactRatio) {}
    }

    /**
     * Fits offsets and returns the partitioned outcome without writing anything.
     *
     * @param dataset  the reference observations
     * @param minExact minimum in-sample exact ratio to accept a city
     * @return the write/reject/unresolved partition
     */
    public Outcome evaluate(ReferenceDataset dataset, double minExact) {
        SecondsApproachOffsetFitter fitter =
                new SecondsApproachOffsetFitter(registry, engine, ReconstructedMethod.frozen());
        Map<String, CityFit> fits = fitter.fitAll(dataset);

        List<CityFit> written = new ArrayList<>();
        List<Outcome.Rejected> rejected = new ArrayList<>();
        for (CityFit fit : fits.values()) {
            if (fit.exactRatio() >= minExact) {
                written.add(fit);
            } else {
                rejected.add(new Outcome.Rejected(fit.cityId(), fit.exactRatio()));
            }
        }
        List<String> unresolved = dataset.cities().stream()
                .filter(c -> !fits.containsKey(c))
                .toList();
        return new Outcome(List.copyOf(written), List.copyOf(rejected), unresolved);
    }

    /** Runs the command: fit, report to {@code out}, and write the JSON file. */
    public void run(Map<String, String> flags, PrintStream out) {
        Path datasetDir = Path.of(require(flags, "dataset"));
        ReferenceDataset dataset = ReferenceDataset.load(datasetDir);
        String citiesFlag = flags.get("cities");
        if (citiesFlag != null && !citiesFlag.isBlank()) {
            dataset = dataset.filter(parseCities(citiesFlag), null, null);
        }
        double minExact = parseMinExact(flags.get("min-exact"));
        Path output = flags.containsKey("output") ? Path.of(flags.get("output")) : DEFAULT_OUTPUT;

        // Base cities keep their committed offsets verbatim; only new cities are fitted.
        Map<String, Map<Prayer, Integer>> base = flags.containsKey("base")
                ? loadOffsets(Path.of(flags.get("base"))) : Map.of();

        Outcome outcome = evaluate(dataset, minExact);

        // Final ordered offsets: preserved base cities + newly-fitted cities, all by slug.
        Map<String, Map<Prayer, Integer>> finalOffsets = new java.util.TreeMap<>(base);
        List<String> preserved = new ArrayList<>();
        List<CityFit> added = new ArrayList<>();
        for (CityFit fit : outcome.written()) {
            if (base.containsKey(fit.cityId())) {
                preserved.add(fit.cityId());
            } else {
                finalOffsets.put(fit.cityId(), fit.offsets());
                added.add(fit);
            }
        }

        write(output, finalOffsets, minExact);

        out.printf(Locale.ROOT, "Fitted sec-approach offsets from %s (min-exact=%.0f%%)%n",
                datasetDir, 100 * minExact);
        for (String city : preserved) {
            out.printf(Locale.ROOT, "  %-14s PRESERVED from base%n", city);
        }
        for (CityFit fit : added) {
            out.printf(Locale.ROOT, "  %-14s exact=%.2f%%  (%d days)%n",
                    fit.cityId(), 100 * fit.exactRatio(), fit.dayCount());
        }
        for (Outcome.Rejected r : outcome.rejected()) {
            out.printf(Locale.ROOT, "  %-14s SKIPPED below band (exact=%.2f%%)%n",
                    r.cityId(), 100 * r.exactRatio());
        }
        for (String u : outcome.unresolved()) {
            out.printf(Locale.ROOT, "  %-14s SKIPPED not in city registry%n", u);
        }
        out.printf(Locale.ROOT, "Wrote %d cities to %s%n", finalOffsets.size(), output);
    }

    /** Writes the offsets file in the committed schema (one city object per line). */
    void write(Path output, Map<String, Map<Prayer, Integer>> cities, double minExact) {
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            try (Writer w = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                w.write("{\n");
                w.write("  \"parameterVersion\": \"morocco-habous-secaproach-v1\",\n");
                w.write("  \"note\": \"Sec-approach: per-city, per-prayer CONSTANT seconds offset "
                        + "added to the reconstructed raw astronomical time, then floored to the "
                        + "minute. Fitted in-sample against the official Habous research oracle by "
                        + "'research fit-secaproach' (min-exact=" + pct(minExact) + "). A city-level "
                        + "calculation parameter, NOT a per-date override.\",\n");
                w.write("  \"model\": \"publishedMinute = floor((rawSeconds + "
                        + "offsetSeconds[city][prayer]) / 60)\",\n");
                w.write("  \"offsets\": {\n");
                int i = 0;
                for (Map.Entry<String, Map<Prayer, Integer>> e : cities.entrySet()) {
                    w.write("    " + quote(e.getKey()) + ": " + offsetsObject(e.getValue()));
                    w.write(++i < cities.size() ? ",\n" : "\n");
                }
                w.write("  }\n");
                w.write("}\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + output, e);
        }
    }

    /** Loads an existing sec-approach offsets file (for {@code --base}). */
    static Map<String, Map<Prayer, Integer>> loadOffsets(Path file) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading base offsets " + file, e);
        }
        OffsetsFile parsed = new com.google.gson.Gson().fromJson(json, OffsetsFile.class);
        if (parsed == null || parsed.offsets == null) {
            throw new IllegalArgumentException("no offsets in base file: " + file);
        }
        Map<String, Map<Prayer, Integer>> out = new java.util.TreeMap<>();
        parsed.offsets.forEach((city, o) -> {
            Map<Prayer, Integer> byPrayer = new java.util.EnumMap<>(Prayer.class);
            byPrayer.put(Prayer.FAJR, o.fajr);
            byPrayer.put(Prayer.SUNRISE, o.sunrise);
            byPrayer.put(Prayer.DHUHR, o.dhuhr);
            byPrayer.put(Prayer.ASR, o.asr);
            byPrayer.put(Prayer.MAGHRIB, o.maghrib);
            byPrayer.put(Prayer.ISHA, o.isha);
            out.put(city.trim().toLowerCase(Locale.ROOT), byPrayer);
        });
        return out;
    }

    private static final class OffsetsFile {
        Map<String, CityOffsets> offsets;
    }

    private static final class CityOffsets {
        int fajr;
        int sunrise;
        int dhuhr;
        int asr;
        int maghrib;
        int isha;
    }

    private static String offsetsObject(Map<Prayer, Integer> offsets) {
        StringBuilder sb = new StringBuilder("{");
        Prayer[] order = {
            Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA
        };
        String[] keys = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
        for (int i = 0; i < order.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(keys[i]).append("\":").append(offsets.get(order[i]));
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        return '"' + s + '"';
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", 100 * ratio);
    }

    private static java.util.Set<String> parseCities(String value) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static double parseMinExact(String value) {
        if (value == null) {
            return DEFAULT_MIN_EXACT;
        }
        try {
            double v = Double.parseDouble(value);
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException("--min-exact must be in [0,1] but was " + value);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid --min-exact: " + value);
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
