package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Offline integrity guard for the committed research snapshots under
 * {@code research-data/habous}. Every verified city must have the same validated
 * 30-day window used by the sec-approach training data, with six values a day and
 * complete provenance — no fabricated, interpolated or gap-filled days.
 *
 * <p>This test never contacts Habous; it only reads the committed files.
 */
class HabousResearchDatasetTest {

    private static final Path DATASET = Path.of("research-data/habous");
    private static final LocalDate FIRST = LocalDate.parse("2026-06-17");
    private static final LocalDate LAST = LocalDate.parse("2026-07-16");
    private static final int DAYS = 30;

    private final ReferenceDataset dataset = ReferenceDataset.load(DATASET);

    @Test
    void everyCityHasThirtyCompleteInWindowContiguousDays() {
        Map<String, List<Observation>> byCity = new TreeMap<>();
        for (Observation o : dataset.observations()) {
            byCity.computeIfAbsent(o.city(), k -> new java.util.ArrayList<>()).add(o);
        }
        for (Map.Entry<String, List<Observation>> e : byCity.entrySet()) {
            List<LocalDate> dates = e.getValue().stream()
                    .map(Observation::date).sorted().distinct().toList();
            assertThat(dates).as("%s dates", e.getKey()).hasSize(DAYS);
            assertThat(dates.get(0)).as("%s first", e.getKey()).isEqualTo(FIRST);
            assertThat(dates.get(dates.size() - 1)).as("%s last", e.getKey()).isEqualTo(LAST);
            // 6 values a day, all present (ReferenceDataset requires all six to parse).
            for (Observation o : e.getValue()) {
                for (Prayer p : Prayer.values()) {
                    assertThat(o.timeFor(p)).as("%s %s %s", e.getKey(), o.date(), p).isNotNull();
                }
            }
        }
    }

    @Test
    void everyVerifiedCityHasASnapshot() {
        List<String> verified = HabousCityMapping.load().verifiedCityIds();
        assertThat(dataset.cities()).as("snapshot for every verified city").containsAll(verified);
        // One observation per city-day (each holds the six daily values): 60 x 30.
        // That is 60 x 180 = 10800 individual prayer-time values.
        assertThat(dataset.observations()).hasSize(verified.size() * DAYS);
    }

    @Test
    void everySnapshotFileCarriesFullProvenance() {
        try (Stream<Path> files = Files.list(DATASET)) {
            List<Path> jsons = files.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            assertThat(jsons).hasSize(60);
            for (Path file : jsons) {
                JsonObject o = new Gson().fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                assertThat(o.keySet()).as("provenance in %s", file.getFileName()).contains(
                        "city", "habousVilleId", "habousArabicName", "sourceUrl",
                        "fetchedAt", "sourceSha256", "parserVersion", "days");
                assertThat(o.getAsJsonArray("days")).as("30 days in %s", file.getFileName())
                        .hasSize(DAYS);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + DATASET, e);
        }
    }
}
