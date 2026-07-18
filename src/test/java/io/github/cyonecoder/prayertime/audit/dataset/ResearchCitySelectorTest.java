package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The shared research CLI city selectors. */
class ResearchCitySelectorTest {

    @Test
    void namedSelectorsResolveToTheExpectedSets() {
        assertThat(ResearchCitySelector.resolve("all-candidates")).hasSize(67);
        assertThat(ResearchCitySelector.resolve("all-67")).hasSize(67);
        assertThat(ResearchCitySelector.resolve("all-mapped")).hasSize(60);
        assertThat(ResearchCitySelector.resolve("all-new-verified")).hasSize(46);
        assertThat(ResearchCitySelector.resolve("all-sec-supported")).hasSize(48);
        assertThat(ResearchCitySelector.resolve("existing-sec")).hasSize(14);
    }

    @Test
    void existingSecAndNewVerifiedAreDisjointAndCoverTheMapping() {
        var existing = ResearchCitySelector.resolve("existing-sec");
        var newVerified = ResearchCitySelector.resolve("all-new-verified");
        assertThat(existing).doesNotContainAnyElementsOf(newVerified);
        // existing-sec (14) are all supported and mapped.
        assertThat(ResearchCitySelector.resolve("all-sec-supported")).containsAll(existing);
        assertThat(ResearchCitySelector.resolve("all-mapped")).containsAll(existing).containsAll(newVerified);
    }

    @Test
    void explicitCommaListIsCaseInsensitiveAndDeduplicated() {
        assertThat(ResearchCitySelector.resolve("al-hoceima, Assilah ,azrou,al-hoceima"))
                .containsExactly("al-hoceima", "assilah", "azrou");
    }

    @Test
    void blankIsRejected() {
        assertThatThrownBy(() -> ResearchCitySelector.resolve("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
