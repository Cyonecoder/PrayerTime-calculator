package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import org.junit.jupiter.api.Test;

/**
 * Coverage guard: every mapped Habous-supported city must have a valid bundled
 * official snapshot, and no bundled snapshot may fail validation on load.
 */
class HabousCityCoverageTest {

    private final OfficialHabousStore store = new OfficialHabousStore();

    @Test
    void everyMappedCityHasAValidBundledSnapshot() {
        HabousCityMapping mapping = HabousCityMapping.load();
        assertThat(store.supportedCityIds())
                .as("official snapshots for all verified (production-official) cities")
                .containsAll(mapping.verifiedCityIds());
    }

    @Test
    void noBundledSnapshotFailsValidation() {
        assertThat(store.rejected())
                .as("rejected snapshots (must be none: empty/partial/wrong-city/gaps)")
                .isEmpty();
    }

    @Test
    void everySnapshotHasContiguousUniqueDatedCoverage() {
        for (String cityId : store.supportedCityIds()) {
            OfficialSnapshot s = store.snapshot(cityId).orElseThrow();
            assertThat(s.dates()).as(cityId + " dates").doesNotHaveDuplicates().isSorted();
            long span = s.dates().get(s.dates().size() - 1).toEpochDay()
                    - s.dates().get(0).toEpochDay() + 1;
            assertThat((long) s.dates().size()).as(cityId + " contiguous").isEqualTo(span);
        }
    }
}
