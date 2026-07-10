package io.github.cyonecoder.prayertime.application.provider;

import com.google.gson.Gson;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only store of the validated official Habous snapshots bundled under
 * {@code /official/habous/}. Loaded once at construction: every snapshot is
 * cross-checked against the Habous city mapping and validated by
 * {@link OfficialSnapshotValidator}; snapshots that fail validation are
 * <strong>rejected</strong> (recorded, never served).
 *
 * <p>The store never modifies, rounds, or corrects official times. It also
 * guards live updates via {@link #offer}, which refuses to replace a good
 * snapshot with an invalid one (empty/partial/wrong-city/wrong-month/error page).
 */
public final class OfficialHabousStore {

    private static final String INDEX = "/official/habous/index.json";
    private static final Gson GSON = new Gson();

    /** A snapshot that failed validation and is not served. */
    public record Rejected(String cityId, String file, List<String> problems) {
    }

    private final Map<String, OfficialSnapshot> byCity = new LinkedHashMap<>();
    private final List<Rejected> rejected = new ArrayList<>();
    private final OfficialSnapshotValidator validator;
    private final HabousCityMapping mapping;

    /** Loads the bundled snapshots using the default mapping and validator. */
    public OfficialHabousStore() {
        this(HabousCityMapping.load(), new OfficialSnapshotValidator());
    }

    public OfficialHabousStore(HabousCityMapping mapping, OfficialSnapshotValidator validator) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.validator = Objects.requireNonNull(validator, "validator");
        loadBundled();
    }

    private void loadBundled() {
        IndexFile index = readJson(INDEX, IndexFile.class);
        if (index == null || index.snapshots == null) {
            return;
        }
        for (IndexEntry e : index.snapshots) {
            SnapshotFile file = readJson("/official/habous/" + e.file, SnapshotFile.class);
            if (file == null) {
                rejected.add(new Rejected(e.cityId, e.file, List.of("resource not found or unreadable")));
                continue;
            }
            OfficialSnapshot snapshot;
            try {
                snapshot = toSnapshot(file);
            } catch (RuntimeException ex) {
                rejected.add(new Rejected(e.cityId, e.file, List.of("parse error: " + ex.getMessage())));
                continue;
            }
            String expectedHabousId = mapping.forCity(snapshot.cityId())
                    .map(HabousCityMapping.Entry::habousCityId).orElse(null);
            OfficialSnapshotValidator.Result result =
                    validator.validate(snapshot, snapshot.cityId(), expectedHabousId);
            if (result.valid()) {
                byCity.put(normalize(snapshot.cityId()), snapshot);
            } else {
                rejected.add(new Rejected(snapshot.cityId(), e.file, result.problems()));
            }
        }
    }

    /**
     * Offers a freshly-parsed snapshot to replace the stored one for its city.
     * The replacement is accepted only if it validates; otherwise the previous
     * valid snapshot is preserved unchanged and the reasons are returned.
     *
     * @param candidate the parsed candidate snapshot
     * @return the validation result (invalid ⇒ nothing changed)
     */
    public OfficialSnapshotValidator.Result offer(OfficialSnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String expectedHabousId = mapping.forCity(candidate.cityId())
                .map(HabousCityMapping.Entry::habousCityId).orElse(null);
        OfficialSnapshotValidator.Result result =
                validator.validate(candidate, candidate.cityId(), expectedHabousId);
        if (result.valid()) {
            byCity.put(normalize(candidate.cityId()), candidate);
        }
        return result;
    }

    /** @return the validated snapshot for a city, if bundled and valid. */
    public Optional<OfficialSnapshot> snapshot(String cityId) {
        return Optional.ofNullable(byCity.get(normalize(cityId)));
    }

    /** @return the official times for a city/date, if a valid snapshot covers it. */
    public Optional<PrayerTimes> find(String cityId, LocalDate date) {
        return snapshot(cityId).flatMap(s -> s.day(date));
    }

    /** @return application city slugs with a valid bundled snapshot, sorted. */
    public List<String> supportedCityIds() {
        return byCity.values().stream().map(OfficialSnapshot::cityId).sorted().toList();
    }

    /** @return snapshots that failed validation on load (never served). */
    public List<Rejected> rejected() {
        return List.copyOf(rejected);
    }

    private OfficialSnapshot toSnapshot(SnapshotFile f) {
        Map<LocalDate, PrayerTimes> days = new TreeMap<>();
        for (DayRow r : f.days) {
            LocalDate date = LocalDate.parse(r.date);
            days.put(date, new PrayerTimes(date,
                    LocalTime.parse(r.fajr), LocalTime.parse(r.sunrise), LocalTime.parse(r.dhuhr),
                    LocalTime.parse(r.asr), LocalTime.parse(r.maghrib), LocalTime.parse(r.isha)));
        }
        return new OfficialSnapshot(f.city, f.habousVilleId, f.sourceSha256, f.parserVersion,
                Instant.parse(f.fetchedAt), days);
    }

    private static <T> T readJson(String resource, Class<T> type) {
        try (InputStream in = OfficialHabousStore.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), type);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("reading " + resource, e);
        }
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    // --- wire DTOs (match the bundled JSON shape) ---------------------------
    private static final class IndexFile {
        List<IndexEntry> snapshots;
    }

    private static final class IndexEntry {
        String cityId;
        String file;
    }

    private static final class SnapshotFile {
        String city;
        String habousVilleId;
        String sourceSha256;
        String parserVersion;
        String fetchedAt;
        List<DayRow> days;
    }

    private static final class DayRow {
        String date;
        String fajr;
        String sunrise;
        String dhuhr;
        String asr;
        String maghrib;
        String isha;
    }
}
