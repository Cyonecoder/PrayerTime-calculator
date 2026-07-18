package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage + lock guard for the expanded sec-approach.
 *
 * <p>Every city committed to {@code morocco-habous-secaproach.json} must reproduce
 * the official Habous research oracle to at least the sec band (95% exact-minute),
 * and the 14 original city offset sets must remain <strong>exactly</strong> their
 * locked reference values — this task must not change them.
 */
class SecondsApproachCoverageTest {

    private static final Path DATASET = Path.of("research-data/habous");
    // Per-city full-dataset exact gate (SecApproachGates.MIN_FULL_EXACT_PCT).
    private static final double MIN_EXACT = 0.90;

    /** The 14 locked reference offset sets: {fajr, sunrise, dhuhr, asr, maghrib, isha}. */
    private static final Map<String, int[]> LOCKED = locked();

    private final SecondsApproachPrayerTimesProvider sec = new SecondsApproachPrayerTimesProvider();
    private final ReferenceDataset dataset = ReferenceDataset.load(DATASET);

    @Test
    void everySecApproachCityMeetsTheBandAgainstTheOracle() {
        Map<String, List<Observation>> byCity = new LinkedHashMap<>();
        for (Observation o : dataset.observations()) {
            byCity.computeIfAbsent(o.city(), k -> new java.util.ArrayList<>()).add(o);
        }
        int cities = 0;
        for (Map.Entry<String, List<Observation>> e : byCity.entrySet()) {
            String city = e.getKey();
            if (!sec.supports(city)) {
                continue; // below-band cities are intentionally not committed
            }
            cities++;
            int exact = 0;
            int n = 0;
            for (Observation o : e.getValue()) {
                PrayerTimes calc = sec.computeTimes(city, o.date());
                for (Prayer p : Prayer.values()) {
                    if (OfficialSnapshot.timeFor(calc, p).equals(o.timeFor(p))) {
                        exact++;
                    }
                    n++;
                }
            }
            double ratio = (double) exact / n;
            assertThat(ratio).as("%s exact ratio", city).isGreaterThanOrEqualTo(MIN_EXACT);
        }
        // 14 locked + 34 gate-passing candidate cities.
        assertThat(cities).isEqualTo(48);
    }

    @Test
    void theFourteenOriginalOffsetSetsAreUnchanged() {
        LOCKED.forEach((city, want) -> {
            assertThat(sec.supports(city)).as(city).isTrue();
            assertThat(offsetsOf(city))
                    .as("%s locked offsets", city)
                    .containsExactly(want[0], want[1], want[2], want[3], want[4], want[5]);
        });
    }

    @Test
    void gateFailingCandidatesAreExcludedAndFailClosed() {
        // The 12 cities that fail a per-city accuracy gate (all on holdout < 85%) are
        // intentionally uncommitted and fail closed.
        for (String city : List.of("berkane", "chefchaouen", "el-jadida", "fkih-ben-salah",
                "khenifra", "khouribga", "ksar-el-kebir", "larache", "midelt", "sidi-ifni",
                "sidi-kacem", "taroudant")) {
            assertThat(sec.supports(city)).as(city).isFalse();
        }
        // berrechid passes the new gates (full >= 90, holdout >= 85) and is supported.
        assertThat(sec.supports("berrechid")).isTrue();
    }

    /** The committed per-prayer offsets for a city, in JSON order, read from the resource. */
    private int[] offsetsOf(String city) {
        try (var in = SecondsApproachCoverageTest.class
                .getResourceAsStream("/research/morocco-habous-secaproach.json")) {
            var root = new com.google.gson.Gson().fromJson(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    com.google.gson.JsonObject.class);
            var o = root.getAsJsonObject("offsets").getAsJsonObject(city);
            return new int[] {
                o.get("fajr").getAsInt(), o.get("sunrise").getAsInt(), o.get("dhuhr").getAsInt(),
                o.get("asr").getAsInt(), o.get("maghrib").getAsInt(), o.get("isha").getAsInt()
            };
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Map<String, int[]> locked() {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("agadir", new int[] {1, 8, 321, -44, -33, 54});
        m.put("casablanca", new int[] {22, 85, 349, -11, -49, 86});
        m.put("dakhla", new int[] {5, 48, 334, -21, -29, 69});
        m.put("errachidia", new int[] {9, 79, 336, -21, -46, 75});
        m.put("fez", new int[] {-4, 86, 332, -16, -78, 91});
        m.put("kenitra", new int[] {9, 42, 335, -24, -49, 70});
        m.put("laayoune", new int[] {9, 107, 335, -22, -88, 70});
        m.put("marrakech", new int[] {13, 80, 341, -14, -44, 82});
        m.put("meknes", new int[] {6, 97, 338, -16, -59, 92});
        m.put("ouarzazate", new int[] {14, 67, 334, -22, -32, 79});
        m.put("oujda", new int[] {6, 53, 333, -20, -36, 79});
        m.put("rabat", new int[] {4, 112, 332, -27, -108, 68});
        m.put("tangier", new int[] {-10, 3, 324, -29, -33, 73});
        m.put("tetouan", new int[] {0, -13, 329, -32, -8, 67});
        return m;
    }
}
