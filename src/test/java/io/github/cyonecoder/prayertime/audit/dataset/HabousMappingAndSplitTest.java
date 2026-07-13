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
        // Ifrane now exists as an application city and maps to its ville id (يفرن).
        assertThat(mapping.forCity("ifrane").orElseThrow().habousCityId()).isEqualTo("100");
        // Seven candidate cities have no dedicated Habous page and stay unmapped
        // (no fallback): kabila, sale, sidi-rahal, tamansourt, tamesna, temara, bouskoura.
        assertThat(mapping.forCity("sale")).isEmpty();
        assertThat(mapping.forCity("temara")).isEmpty();
        assertThat(mapping.forCity("bouskoura")).isEmpty();
        // Arabic name is preserved intact, and the verified cities carry their
        // real numeric Habous ville id.
        assertThat(mapping.forCity("casablanca").orElseThrow().habousArabicName())
                .isEqualTo("الدار البيضاء");
        assertThat(mapping.forCity("casablanca").orElseThrow().habousCityId()).isEqualTo("58");
        assertThat(mapping.forCity("rabat").orElseThrow().habousCityId()).isEqualTo("1");
        assertThat(mapping.forCity("fez").orElseThrow().habousCityId()).isEqualTo("81");
        assertThat(mapping.forCity("laayoune").orElseThrow().habousCityId()).isEqualTo("156");
        // The ten formerly-placeholder ids were recovered from the official ville
        // dropdown and are now real numeric ids.
        assertThat(mapping.forCity("tangier").orElseThrow().habousCityId()).isEqualTo("14");
        assertThat(mapping.forCity("kenitra").orElseThrow().habousCityId()).isEqualTo("7");
        assertThat(mapping.forCity("ouarzazate").orElseThrow().habousCityId()).isEqualTo("138");

        // Every mapped city carries a non-blank numeric ville id.
        assertThat(mapping.mappedCityIds()).allSatisfy(city ->
                assertThat(mapping.forCity(city).orElseThrow().habousCityId()).isNotBlank());
        // The 14 original cities are hand-verified; the added candidate cities are
        // index-matched (verified=false) and confirmed at fetch time instead.
        assertThat(mapping.forCity("casablanca").orElseThrow().verified()).isTrue();
        assertThat(mapping.forCity("tangier").orElseThrow().verified()).isTrue();
        assertThat(mapping.forCity("safi").orElseThrow().verified()).isFalse();
        assertThat(mapping.forCity("safi").orElseThrow().habousCityId()).isEqualTo("111");
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
