package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Candidate;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Result;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Status;
import io.github.cyonecoder.prayertime.audit.dataset.HabousVilleDirectory.Option;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles {@code research verify-habous-cities}: independently re-verifies every
 * candidate city against the <em>live</em> Habous source and records the outcome.
 *
 * <p>It fetches the official {@code <select name="ville">} dropdown, parses all
 * options into an audit file, verifies each candidate (see {@link
 * HabousCityVerifier}), writes a per-candidate verification report, and rewrites
 * {@code habous-city-mapping.json} with <strong>only</strong> the verified
 * mappings plus provenance — keeping the runtime schema stable.
 *
 * <pre>research verify-habous-cities [--delay-seconds 1] [--dropdown-ville 1]
 *   [--mapping &lt;file&gt;] [--options-out &lt;file&gt;] [--report-out &lt;file&gt;]</pre>
 */
public final class HabousCityVerificationCommand {

    private static final String INVENTORY = "/research/habous-candidate-inventory.json";
    private static final String CITIES = "/cities/morocco-cities.json";
    private static final Path DEFAULT_MAPPING =
            Path.of("src/main/resources/research/habous-city-mapping.json");
    private static final Path DEFAULT_OPTIONS_OUT =
            Path.of("build/reports/secapproach-all-cities/habous-live-city-options.json");
    private static final Path DEFAULT_REPORT_OUT =
            Path.of("reports/secapproach-all-cities/habous-city-verification.json");

    private final Fetcher fetcher;
    private final Clock clock;

    public HabousCityVerificationCommand() {
        this(HabousClient.withHttpClient(HabousResearchTls.trustingHttpClient())::fetchMonthlyHtml,
                Clock.systemUTC());
    }

    public HabousCityVerificationCommand(Fetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /** Runs the verification and writes the audit outputs. */
    public void run(Map<String, String> flags, PrintStream out) {
        long delay = parseLong(flags.get("delay-seconds"), 1);
        String dropdownVille = flags.getOrDefault("dropdown-ville", "1");
        Path mappingFile = path(flags, "mapping", DEFAULT_MAPPING);
        Path optionsOut = path(flags, "options-out", DEFAULT_OPTIONS_OUT);
        Path reportOut = path(flags, "report-out", DEFAULT_REPORT_OUT);

        // 1. Fetch + parse the live ville dropdown.
        String dropdownHtml = fetcher.fetchMonthlyHtml(dropdownVille, java.time.LocalDate.now(clock));
        HabousVilleDirectory directory = HabousVilleDirectory.parse(dropdownHtml);
        String fetchedAt = clock.instant().toString();
        writeOptionsAudit(optionsOut, directory, dropdownVille, fetchedAt);
        out.println("Parsed " + directory.options().size() + " ville options (checksum "
                + directory.sourceChecksum().substring(0, 12) + "…) → " + optionsOut);

        // 2. Load candidates (inventory) with registry aliases, and prior ids.
        List<Candidate> candidates = loadCandidates();
        Map<String, String> verifiedIds = loadMappingIds(mappingFile, true);
        Map<String, String> proposedIds = loadMappingIds(mappingFile, false);

        // 3. Verify each candidate live.
        HabousCityVerifier verifier = new HabousCityVerifier(
                fetcher, new HabousLiveHtmlParser(), clock,
                Duration.ofSeconds(delay), d -> Thread.sleep(d.toMillis()));
        List<Result> results = verifier.verifyAll(candidates, directory, verifiedIds, proposedIds);

        // 4. Write the per-candidate verification report.
        writeVerificationReport(reportOut, results, directory, fetchedAt);

        // 5. Rewrite the mapping with only VERIFIED entries + provenance.
        int written = rewriteMapping(mappingFile, results, directory.sourceChecksum(), fetchedAt);

        // 6. Summarize.
        EnumMap<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Result r : results) {
            counts.merge(r.status(), 1, Integer::sum);
            out.printf(Locale.ROOT, "  %-16s %-22s %s%n",
                    r.candidate().canonicalSlug(), r.status(),
                    r.habousCityId() == null ? r.message()
                            : "ville=" + r.habousCityId() + " (" + r.habousArabicName() + ")");
        }
        out.println("Status summary:");
        counts.forEach((s, n) -> out.printf(Locale.ROOT, "  %-24s %d%n", s, n));
        out.println("Report → " + reportOut);
        out.println("Wrote " + written + " verified mappings → " + mappingFile);
    }

    // --- Loading --------------------------------------------------------------

    private List<Candidate> loadCandidates() {
        JsonObject inv = readResource(INVENTORY);
        Map<String, List<String>> aliases = loadAliases();
        List<Candidate> out = new ArrayList<>();
        for (JsonElement e : inv.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            String slug = c.get("canonicalSlug").getAsString();
            out.add(new Candidate(slug,
                    c.get("sourceValue").getAsString(),
                    c.get("arabicName").getAsString(),
                    aliases.getOrDefault(slug, List.of())));
        }
        return out;
    }

    private Map<String, List<String>> loadAliases() {
        JsonObject cities = readResource(CITIES);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (JsonElement e : cities.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            List<String> al = new ArrayList<>();
            if (c.has("aliases")) {
                for (JsonElement a : c.getAsJsonArray("aliases")) {
                    al.add(a.getAsString());
                }
            }
            out.put(c.get("slug").getAsString(), al);
        }
        return out;
    }

    private static Map<String, String> loadMappingIds(Path mappingFile, boolean verified) {
        JsonObject root = readFile(mappingFile);
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonElement e : root.getAsJsonArray("mappings")) {
            JsonObject m = e.getAsJsonObject();
            boolean v = m.has("verified") && m.get("verified").getAsBoolean();
            if (v == verified) {
                out.put(m.get("applicationCityId").getAsString(), m.get("habousCityId").getAsString());
            }
        }
        return out;
    }

    // --- Writing --------------------------------------------------------------

    private void writeOptionsAudit(
            Path file, HabousVilleDirectory directory, String dropdownVille, String fetchedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Audit-only snapshot of the official Habous <select name=\"ville\"> "
                + "dropdown. NOT a runtime production dependency.");
        root.put("source", HabousClient.monthlyUri(dropdownVille).toString());
        root.put("fetchedAt", fetchedAt);
        root.put("sourceChecksum", directory.sourceChecksum());
        root.put("parserVersion", HabousVilleDirectory.PARSER_VERSION);
        root.put("optionCount", directory.options().size());
        List<Map<String, Object>> opts = new ArrayList<>();
        for (Option o : directory.options()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("habousCityId", o.habousCityId());
            m.put("exactArabicName", o.exactArabicName());
            m.put("visibleLabel", o.visibleLabel());
            m.put("normalizedArabic", o.normalizedArabic());
            m.put("foldedArabic", o.foldedArabic());
            opts.add(m);
        }
        root.put("options", opts);
        writeJson(file, root);
    }

    private void writeVerificationReport(
            Path file, List<Result> results, HabousVilleDirectory directory, String fetchedAt) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Independent live re-verification of every candidate city against the "
                + "Habous <select name=\"ville\"> dropdown and its timetable. Research/audit only.");
        root.put("verificationMethod", HabousCityVerifier.METHOD);
        root.put("verifiedAt", fetchedAt);
        root.put("dropdownChecksum", directory.sourceChecksum());
        root.put("parserVersion", HabousVilleDirectory.PARSER_VERSION);

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            summary.put(s.name(), 0);
        }
        for (Result r : results) {
            summary.merge(r.status().name(), 1, Integer::sum);
        }
        summary.put("total", results.size());
        root.put("summary", summary);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Result r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("canonicalSlug", r.candidate().canonicalSlug());
            m.put("sourceValue", r.candidate().sourceValue());
            m.put("candidateArabicName", r.candidate().arabicName());
            m.put("status", r.status().name());
            m.put("matchMethod", r.matchMethod());
            m.put("habousCityId", r.habousCityId());
            m.put("habousArabicName", r.habousArabicName());
            m.put("dayCount", r.dayCount());
            m.put("firstDate", r.firstDate() == null ? null : r.firstDate().toString());
            m.put("lastDate", r.lastDate() == null ? null : r.lastDate().toString());
            m.put("sourceChecksum", r.sourceChecksum());
            m.put("confirmChecksum", r.confirmChecksum());
            m.put("confirmed", r.confirmed());
            m.put("message", r.message());
            rows.add(m);
        }
        root.put("candidates", rows);
        writeJson(file, root);
    }

    private int rewriteMapping(
            Path mappingFile, List<Result> results, String dropdownChecksum, String verifiedAt) {
        JsonObject root = readFile(mappingFile);
        JsonArray mappings = new JsonArray();
        int written = 0;
        for (Result r : results) {
            if (!r.isVerified()) {
                continue;
            }
            JsonObject m = new JsonObject();
            m.addProperty("applicationCityId", r.candidate().canonicalSlug());
            m.addProperty("habousCityId", r.habousCityId());
            m.addProperty("habousArabicName", r.habousArabicName());
            m.addProperty("verified", true);
            m.addProperty("verificationMethod", HabousCityVerifier.METHOD);
            m.addProperty("verifiedAt", verifiedAt);
            m.addProperty("sourceChecksum", r.sourceChecksum());
            mappings.add(m);
            written++;
        }
        root.add("mappings", mappings);
        root.addProperty("version", 4);
        root.addProperty("coverageNote", written + " of the candidate cities are VERIFIED against "
                + "the live Habous select+timetable (verified=true). Cities not listed here are "
                + "NOT_LISTED_BY_HABOUS, AMBIGUOUS_NAME, TIMETABLE_UNAVAILABLE, INVALID_TIMETABLE, "
                + "or MANUAL_REVIEW_REQUIRED — see reports/secapproach-all-cities/"
                + "habous-city-verification.json. No fallback is used; unverified cities fail closed.");
        writeJsonElement(mappingFile, root);
        return written;
    }

    // --- Helpers --------------------------------------------------------------

    private static JsonObject readResource(String resource) {
        try (InputStream in = HabousCityVerificationCommand.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + resource);
            }
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }

    private static JsonObject readFile(Path file) {
        try {
            return new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }

    private void writeJson(Path file, Object root) {
        writeElement(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJsonTree(root));
    }

    private void writeJsonElement(Path file, JsonElement element) {
        writeElement(file, element);
    }

    private void writeElement(Path file, JsonElement element) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element, w);
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("writing " + file, e);
        }
    }

    private static Path path(Map<String, String> flags, String key, Path fallback) {
        return flags.containsKey(key) ? Path.of(flags.get(key)) : fallback;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            long v = Long.parseLong(value);
            if (v < 0) {
                throw new IllegalArgumentException("must be >= 0: " + value);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number: " + value);
        }
    }
}
