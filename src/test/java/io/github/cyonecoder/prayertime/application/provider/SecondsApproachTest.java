package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The sec-approach (per-city, per-prayer seconds offset + floor) must materially
 * beat the global formula against the committed official data.
 */
class SecondsApproachTest {

    private final OfficialHabousStore store = new OfficialHabousStore();
    private final SecondsApproachPrayerTimesProvider sec = new SecondsApproachPrayerTimesProvider();
    private final AstronomicalPrayerTimesProvider global = new AstronomicalPrayerTimesProvider();

    private double exactRatio(boolean useSecApproach) {
        int exact = 0;
        int n = 0;
        for (String cityId : store.supportedCityIds()) {
            OfficialSnapshot s = store.snapshot(cityId).orElseThrow();
            for (LocalDate date : s.dates()) {
                PrayerTimes off = s.day(date).orElseThrow();
                PrayerTimes calc = useSecApproach
                        ? sec.computeTimes(cityId, date) : global.computeTimes(cityId, date);
                for (Prayer p : Prayer.values()) {
                    if (OfficialSnapshot.timeFor(off, p).equals(OfficialSnapshot.timeFor(calc, p))) {
                        exact++;
                    }
                    n++;
                }
            }
        }
        return (double) exact / n;
    }

    @Test
    void secApproachReachesItsAccuracyBandAndBeatsTheGlobalFormula() {
        double secExact = exactRatio(true);
        double globalExact = exactRatio(false);
        System.out.printf("[sec-approach] exact=%.2f%%  (global=%.2f%%)%n",
                100 * secExact, 100 * globalExact);
        assertThat(secExact).isGreaterThanOrEqualTo(0.95);
        assertThat(secExact).isGreaterThan(globalExact + 0.10);
    }

    @Test
    void everyMappedCityIsSupported() {
        for (String cityId : store.supportedCityIds()) {
            assertThat(sec.supports(cityId)).as(cityId).isTrue();
        }
    }

    @Test
    void unsupportedCityFailsClosed() {
        assertThatThrownBy(() -> sec.computeTimes("atlantis", LocalDate.parse("2026-07-07")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secondPreciseTimeFloorsToThePublishedMinute() {
        LocalDate date = LocalDate.parse("2026-07-07");
        for (Prayer p : Prayer.values()) {
            var raw = sec.secondPreciseTime("casablanca", date, p);
            var published = OfficialSnapshot.timeFor(sec.computeTimes("casablanca", date), p);
            // Published minute == floor(raw to the minute).
            assertThat(published).isEqualTo(raw.withSecond(0).withNano(0));
        }
    }

    // --- Task 12.5 coverage extensions ---------------------------------------

    private static final java.nio.file.Path DATASET = java.nio.file.Path.of("research-data/habous");

    private java.util.List<String> supportedDatasetCities() {
        return io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.load(DATASET)
                .cities().stream().filter(sec::supports).toList();
    }

    @Test
    void everySupportedCityHasAllSixOffsetsAndFloorsToThePublishedMinute() {
        LocalDate date = LocalDate.parse("2026-07-07");
        for (String city : supportedDatasetCities()) {
            PrayerTimes t = sec.computeTimes(city, date);
            for (Prayer p : Prayer.values()) {
                assertThat(OfficialSnapshot.timeFor(t, p)).as("%s %s present", city, p).isNotNull();
                assertThat(OfficialSnapshot.timeFor(t, p))
                        .as("%s %s floors", city, p)
                        .isEqualTo(sec.secondPreciseTime(city, date, p).withSecond(0).withNano(0));
            }
        }
    }

    @Test
    void gateFailingAndUnmappedCitiesFailClosed() {
        for (String city : java.util.List.of("larache", "sidi-kacem", "temara", "atlantis")) {
            assertThat(sec.supports(city)).as(city).isFalse();
            assertThatThrownBy(() -> sec.computeTimes(city, LocalDate.parse("2026-07-07")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aggregateGatesPassOverEverySupportedCity() {
        var oracle = io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.load(DATASET);
        int exact = 0;
        int within1 = 0;
        int maxAbs = 0;
        int globalExact = 0;
        int n = 0;
        for (var o : oracle.observations()) {
            if (!sec.supports(o.city())) {
                continue;
            }
            PrayerTimes s = sec.computeTimes(o.city(), o.date());
            PrayerTimes g = global.computeTimes(o.city(), o.date());
            for (Prayer p : Prayer.values()) {
                int off = minuteOf(o.timeFor(p));
                int err = Math.abs(Math.floorMod(minuteOf(OfficialSnapshot.timeFor(s, p)) - off + 720, 1440) - 720);
                if (err == 0) {
                    exact++;
                }
                if (err <= 1) {
                    within1++;
                }
                maxAbs = Math.max(maxAbs, err);
                if (minuteOf(OfficialSnapshot.timeFor(g, p)) == off) {
                    globalExact++;
                }
                n++;
            }
        }
        double exactPct = 100.0 * exact / n;
        var gate = io.github.cyonecoder.prayertime.audit.secapproach.SecApproachGates.aggregate(
                exactPct, exactPct - 100.0 * globalExact / n, 100.0 * within1 / n, maxAbs);
        assertThat(gate.failures()).isEmpty();
    }

    @Test
    void everyAcceptedCityPassesItsPerCityGate() {
        var catalog = readJson("reports/secapproach-all-cities/new-city-training.json");
        for (var e : catalog.getAsJsonArray("cities")) {
            var c = e.getAsJsonObject();
            if (!"ACCEPTED".equals(c.get("status").getAsString())) {
                continue;
            }
            var full = c.getAsJsonObject("fullDatasetMetrics");
            var hold = c.getAsJsonObject("holdoutMetrics");
            double within1 = 100.0 * full.get("withinOneCount").getAsInt() / full.get("observations").getAsInt();
            var gate = io.github.cyonecoder.prayertime.audit.secapproach.SecApproachGates.perCity(
                    full.get("exactPercentage").getAsDouble(), hold.get("exactPercentage").getAsDouble(),
                    within1, full.get("maxAbsoluteError").getAsInt());
            assertThat(gate.passed()).as("%s per-city gate", c.get("cityId").getAsString()).isTrue();
        }
    }

    @Test
    void existingFourteenPredictionsAreUnchanged() {
        // Pinned reference for a locked city; any drift in the 14 offsets would break it.
        PrayerTimes rabat = sec.computeTimes("rabat", LocalDate.parse("2026-07-07"));
        assertThat(rabat.fajr()).hasToString("04:32");
        assertThat(rabat.sunrise()).hasToString("06:19");
        assertThat(rabat.dhuhr()).hasToString("13:37");
        assertThat(rabat.asr()).hasToString("17:18");
        assertThat(rabat.maghrib()).hasToString("20:46");
        assertThat(rabat.isha()).hasToString("22:18");
    }

    private static int minuteOf(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static com.google.gson.JsonObject readJson(String path) {
        try {
            return new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(
                    java.nio.file.Path.of(path), java.nio.charset.StandardCharsets.UTF_8),
                    com.google.gson.JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
