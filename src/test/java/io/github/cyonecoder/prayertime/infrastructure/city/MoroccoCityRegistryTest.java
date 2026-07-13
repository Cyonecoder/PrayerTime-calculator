package io.github.cyonecoder.prayertime.infrastructure.city;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.domain.model.City;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class MoroccoCityRegistryTest {

    private static final ZoneId MOROCCO = ZoneId.of("Africa/Casablanca");
    private static final List<String> EXPECTED_SLUGS = List.of(
            // The 15 original registry cities.
            "rabat", "sale", "casablanca", "fez", "marrakech", "tangier", "agadir",
            "oujda", "meknes", "kenitra", "tetouan", "laayoune", "dakhla",
            "errachidia", "ouarzazate",
            // The 52 candidate cities added from the authoritative 67-city inventory.
            "al-hoceima", "assilah", "azrou", "beni-mellal", "benslimane", "berkane",
            "berrechid", "boujdour", "bouskoura", "bouznika", "chefchaouen", "el-hajeb",
            "el-jadida", "essaouira", "smara", "fkih-ben-salah", "guelmim", "guercif",
            "ifrane", "kabila", "khenifra", "khemisset", "khouribga", "ksar-el-kebir",
            "larache", "martil", "melilia", "midelt", "mohammedia", "nador", "oualidia",
            "ouazzane", "safi", "saidia", "sebta", "sefrou", "settat", "sidi-bennour",
            "sidi-ifni", "sidi-kacem", "sidi-rahal", "sidi-slimane", "tamansourt",
            "tamesna", "tan-tan", "tarfaya", "taroudant", "taza", "temara", "tiflet",
            "tinghir", "tiznit");

    private final MoroccoCityRegistry registry = new MoroccoCityRegistry();

    @Test
    void everyExpectedCityLoads() {
        assertThat(registry.all()).hasSize(EXPECTED_SLUGS.size());
        assertThat(registry.all().stream().map(City::id))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SLUGS);
        for (String slug : EXPECTED_SLUGS) {
            assertThat(registry.findById(slug)).isPresent();
        }
    }

    @Test
    void aliasesAndNamesResolveCaseInsensitively() {
        assertThat(registry.findByNameOrAlias("Fes")).map(City::id).contains("fez");
        assertThat(registry.findByNameOrAlias("fès")).map(City::id).contains("fez");
        assertThat(registry.findByNameOrAlias("Tanger")).map(City::id).contains("tangier");
        assertThat(registry.findByNameOrAlias("Marrakesh")).map(City::id).contains("marrakech");
        assertThat(registry.findByNameOrAlias("casa")).map(City::id).contains("casablanca");
        // Official display name also resolves.
        assertThat(registry.findByNameOrAlias("Salé")).map(City::id).contains("sale");
    }

    @Test
    void unknownCityIsNeverDefaultedToCasablanca() {
        assertThat(registry.findById("atlantis")).isEmpty();
        assertThat(registry.findByNameOrAlias("atlantis")).isEmpty();
        assertThat(registry.findById(null)).isEmpty();

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> registry.requireById("atlantis"));
    }

    @Test
    void requireByIdReturnsKnownCity() {
        assertThat(registry.requireById("RABAT").displayName()).isEqualTo("Rabat");
    }

    @Test
    void allCitiesUseAfricaCasablancaZone() {
        assertThat(registry.all()).allSatisfy(city -> assertThat(city.zone()).isEqualTo(MOROCCO));
    }

    @Test
    void allCoordinatesAreWithinValidRanges() {
        assertThat(registry.all()).allSatisfy(city -> {
            assertThat(city.coordinates().latitude()).isBetween(-90.0, 90.0);
            assertThat(city.coordinates().longitude()).isBetween(-180.0, 180.0);
        });
    }

    @Test
    void rejectsDuplicateSlugs() {
        assertThatThrownBy(() -> new MoroccoCityRegistry("/cities/duplicate-slug.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate city slug");
    }

    @Test
    void rejectsConflictingAliases() {
        assertThatThrownBy(() -> new MoroccoCityRegistry("/cities/conflicting-alias.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting alias");
    }

    @Test
    void rejectsInvalidCoordinatesOnLoad() {
        assertThatThrownBy(() -> new MoroccoCityRegistry("/cities/invalid-coordinates.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
    }

    @Test
    void rejectsMissingResource() {
        assertThatThrownBy(() -> new MoroccoCityRegistry("/cities/does-not-exist.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
