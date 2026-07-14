package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The authoritative 67-city candidate inventory resource. */
class AllCandidateCitiesInventoryTest {

    private JsonObject inventory() {
        try (InputStream in = getClass().getResourceAsStream(
                "/research/habous-candidate-inventory.json")) {
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void countsMatchTheAuthoritativeInventory() {
        var cities = inventory().getAsJsonArray("cities");
        assertThat(cities).hasSize(67);

        long active = 0;
        long commented = 0;
        for (JsonElement e : cities) {
            String state = e.getAsJsonObject().get("originalHtmlState").getAsString();
            if ("active".equals(state)) {
                active++;
            } else if ("commented".equals(state)) {
                commented++;
            }
        }
        assertThat(active).as("active").isEqualTo(48);
        assertThat(commented).as("commented").isEqualTo(19);
    }

    @Test
    void hasNoDuplicateCanonicalSlug() {
        List<String> slugs = new ArrayList<>();
        for (JsonElement e : inventory().getAsJsonArray("cities")) {
            slugs.add(e.getAsJsonObject().get("canonicalSlug").getAsString());
        }
        assertThat(slugs).doesNotHaveDuplicates().hasSize(67);
    }

    @Test
    void everyExpectedCityIsRepresented() {
        List<String> slugs = new ArrayList<>();
        for (JsonElement e : inventory().getAsJsonArray("cities")) {
            slugs.add(e.getAsJsonObject().get("canonicalSlug").getAsString());
        }
        assertThat(slugs).contains(
                // the 14 existing-sec cities
                "agadir", "casablanca", "dakhla", "errachidia", "fez", "kenitra", "laayoune",
                "marrakech", "meknes", "ouarzazate", "oujda", "rabat", "tangier", "tetouan",
                // a spread of new candidates, active and commented
                "al-hoceima", "safi", "nador", "sebta", "melilia", "ifrane", "guercif",
                "tan-tan", "sale", "temara", "tamesna", "kabila");
    }

    @Test
    void assertionBlockMatchesTheData() {
        JsonObject a = inventory().getAsJsonObject("assertions");
        assertThat(a.get("totalCandidates").getAsInt()).isEqualTo(67);
        assertThat(a.get("originallyActive").getAsInt()).isEqualTo(48);
        assertThat(a.get("originallyCommented").getAsInt()).isEqualTo(19);
        assertThat(a.get("existingSecCities").getAsInt()).isEqualTo(14);
        assertThat(a.get("citiesRequiringSecTraining").getAsInt()).isEqualTo(53);
    }
}
