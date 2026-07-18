package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the frozen, reviewed sec-approach parameter resource (v2): its version
 * matches the provider constant, it carries the required generator/dataset
 * provenance, its cities are slug-sorted, and it loads in production.
 */
class SecApproachParameterResourceTest {

    private JsonObject resource() {
        try (InputStream in = getClass().getResourceAsStream("/research/morocco-habous-secaproach.json")) {
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void versionMatchesTheProviderConstantAndIsV2() {
        JsonObject r = resource();
        assertThat(r.get("parameterVersion").getAsString())
                .isEqualTo("morocco-habous-secaproach-v2")
                .isEqualTo(SecondsApproachPrayerTimesProvider.PARAMETER_VERSION);
        assertThat(r.get("generatorVersion").getAsString()).isEqualTo("secapproach-trainer-v1");
        assertThat(r.get("model").getAsString())
                .isEqualTo("publishedMinute = floor((rawSeconds + offsetSeconds[city][prayer]) / 60)");
    }

    @Test
    void carriesDatasetProvenance() {
        JsonObject g = resource().getAsJsonObject("generatedFrom");
        assertThat(g.get("firstDate").getAsString()).isEqualTo("2026-06-17");
        assertThat(g.get("lastDate").getAsString()).isEqualTo("2026-07-16");
        assertThat(g.get("datasetChecksum").getAsString()).hasSize(64);
        assertThat(g.get("verifiedCityCount").getAsInt()).isEqualTo(60);
        assertThat(g.get("trainedCityCount").getAsInt())
                .isEqualTo(resource().getAsJsonObject("offsets").size());
    }

    @Test
    void offsetsAreSortedBySlugAndPreserveTheFourteenLockedCities() {
        JsonObject offsets = resource().getAsJsonObject("offsets");
        List<String> cities = new ArrayList<>(offsets.keySet());
        assertThat(cities).isSorted().hasSize(48);

        // The 14 originals stay exactly as shipped.
        for (String base : List.of("agadir", "casablanca", "dakhla", "errachidia", "fez",
                "kenitra", "laayoune", "marrakech", "meknes", "ouarzazate", "oujda", "rabat",
                "tangier", "tetouan")) {
            assertThat(cities).contains(base);
        }
    }

    @Test
    void providerLoadsTheReviewedResource() {
        SecondsApproachPrayerTimesProvider provider = new SecondsApproachPrayerTimesProvider();
        assertThat(provider.supports("casablanca")).isTrue();
        assertThat(provider.supports("safi")).isTrue();
    }

    @Test
    void exactlySixIntegerOffsetsPerCityInsideApprovedLimits() {
        JsonObject offsets = resource().getAsJsonObject("offsets");
        for (String city : offsets.keySet()) {
            JsonObject o = offsets.getAsJsonObject(city);
            assertThat(o.keySet()).as("%s prayers", city)
                    .containsExactlyInAnyOrder("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha");
            for (String prayer : o.keySet()) {
                var el = o.get(prayer);
                assertThat(el.getAsJsonPrimitive().isNumber()).as("%s.%s numeric", city, prayer).isTrue();
                int v = el.getAsInt();
                assertThat((double) v).as("%s.%s integer", city, prayer).isEqualTo(el.getAsDouble());
                // Approved limits = the search window [-900, +900].
                assertThat(Math.abs(v)).as("%s.%s within limit", city, prayer).isLessThanOrEqualTo(900);
            }
        }
    }

    @Test
    void noDuplicateCityEntriesInTheRawResource() throws Exception {
        String raw = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/research/morocco-habous-secaproach.json"),
                StandardCharsets.UTF_8);
        JsonObject offsets = resource().getAsJsonObject("offsets");
        for (String city : offsets.keySet()) {
            int count = raw.split("\"" + city + "\"\\s*:", -1).length - 1;
            assertThat(count).as("%s appears once", city).isEqualTo(1);
        }
    }

    @Test
    void acceptedCitiesHaveParametersAndNonAcceptedHaveNone() {
        JsonObject catalog = readReport("reports/secapproach-all-cities/new-city-training.json");
        java.util.Set<String> committed = resource().getAsJsonObject("offsets").keySet();
        for (var e : catalog.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            String city = c.get("cityId").getAsString();
            if ("ACCEPTED".equals(c.get("status").getAsString())) {
                assertThat(committed).as("%s ACCEPTED has params", city).contains(city);
            } else {
                assertThat(committed).as("%s non-ACCEPTED has no params", city).doesNotContain(city);
            }
        }
    }

    @Test
    void existingFourteenV1OffsetsAreUnchangedInV2() {
        JsonObject v2 = resource().getAsJsonObject("offsets");
        JsonObject base14 = readOffsetsFile(
                "src/main/resources/research/morocco-habous-secaproach-base14.json");
        for (String city : base14.keySet()) {
            assertThat(v2.getAsJsonObject(city)).as("%s unchanged", city)
                    .isEqualTo(base14.getAsJsonObject(city));
        }
    }

    @Test
    void generatedParametersMatchCommittedAfterCanonicalNormalization(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // Regenerate the candidate parameters and confirm they equal the committed
        // resource once both are parsed into their canonical offset maps.
        java.nio.file.Path generated = tmp.resolve("generated-parameters.json");
        new io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingCommand(
                new io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry(),
                new io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine()).run(
                java.util.Map.of(
                        "dataset", "research-data/habous",
                        "base", "src/main/resources/research/morocco-habous-secaproach-base14.json",
                        "offsets-out", generated.toString(),
                        "report-out", tmp.resolve("r.json").toString(),
                        "holdout-out", tmp.resolve("h.json").toString()),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertThat(readOffsetsFile(generated.toString()))
                .isEqualTo(resource().getAsJsonObject("offsets"));
    }

    private static JsonObject readOffsetsFile(String path) {
        return readFile(path).getAsJsonObject("offsets");
    }

    private static JsonObject readReport(String path) {
        return readFile(path);
    }

    private static JsonObject readFile(String path) {
        try {
            return new Gson().fromJson(java.nio.file.Files.readString(
                    java.nio.file.Path.of(path), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
