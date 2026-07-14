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
        assertThat(cities).isSorted().hasSize(56);

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
}
