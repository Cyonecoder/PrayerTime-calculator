package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousDatasetSplit.Split;
import org.junit.jupiter.api.Test;

class HabousMappingAndSplitTest {

    @Test
    void mappingLoadsAllExistingApplicationCitiesAndPreservesArabicNames() {
        HabousCityMapping mapping = HabousCityMapping.load();
        assertThat(mapping.mappedCityIds())
                .contains("casablanca", "rabat", "tangier", "oujda", "marrakech", "agadir",
                        "errachidia", "laayoune", "dakhla", "fez", "meknes", "tetouan",
                        "kenitra", "ouarzazate");
        // Ifrane has no application city, so it is deliberately unmapped.
        assertThat(mapping.forCity("ifrane")).isEmpty();
        // Arabic name is preserved intact, and the verified cities carry their
        // real numeric Habous ville id.
        assertThat(mapping.forCity("casablanca").orElseThrow().habousArabicName())
                .isEqualTo("الدار البيضاء");
        assertThat(mapping.forCity("casablanca").orElseThrow().habousCityId()).isEqualTo("58");
        assertThat(mapping.forCity("rabat").orElseThrow().habousCityId()).isEqualTo("1");
        assertThat(mapping.forCity("fez").orElseThrow().habousCityId()).isEqualTo("81");
        assertThat(mapping.forCity("laayoune").orElseThrow().habousCityId()).isEqualTo("156");

        // Only the confirmed cities are marked verified; the rest remain placeholders.
        assertThat(mapping.forCity("casablanca").orElseThrow().verified()).isTrue();
        assertThat(mapping.forCity("rabat").orElseThrow().verified()).isTrue();
        assertThat(mapping.forCity("fez").orElseThrow().verified()).isTrue();
        assertThat(mapping.forCity("laayoune").orElseThrow().verified()).isTrue();
        assertThat(mapping.forCity("tangier").orElseThrow().verified()).isFalse();
    }

    @Test
    void splitIsFixedDisjointAndCoversTheInitialResearchCities() {
        HabousDatasetSplit split = HabousDatasetSplit.load();
        assertThat(split.training())
                .containsExactlyInAnyOrder("casablanca", "rabat", "marrakech", "oujda", "fez");
        assertThat(split.validation())
                .containsExactlyInAnyOrder("tangier", "agadir", "errachidia", "meknes", "kenitra");
        assertThat(split.holdout())
                .containsExactlyInAnyOrder("ifrane", "laayoune", "dakhla", "tetouan", "ouarzazate");
        assertThat(split.allCities()).hasSize(15);

        assertThat(split.splitFor("casablanca")).contains(Split.TRAINING);
        assertThat(split.splitFor("tangier")).contains(Split.VALIDATION);
        assertThat(split.splitFor("dakhla")).contains(Split.HOLDOUT);
        assertThat(split.splitFor("atlantis")).isEmpty();
    }
}
