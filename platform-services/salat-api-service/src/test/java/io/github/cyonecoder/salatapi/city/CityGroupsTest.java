package io.github.cyonecoder.salatapi.city;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.city.CitySupportService.SupportLevel;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The four city groups (spec 8.1-8.4) and the nesting they must preserve.
 *
 * <p>These counts are not arbitrary: they are the contract the rest of the
 * service reasons about. Guarding them here means a resource swap that silently
 * changes coverage fails the build instead of changing what the API claims to
 * support.
 */
class CityGroupsTest {

    private static MoroccoCityRegistry registry;
    private static OfficialHabousCityDirectory directory;
    private static ApplicationHabousMapping mapping;
    private static SecApproachParameters parameters;
    private static CitySupportService support;

    @BeforeAll
    static void loadBundledResources() {
        registry = new MoroccoCityRegistry();
        directory = new OfficialHabousCityDirectory();
        mapping = new ApplicationHabousMapping();
        parameters = new SecApproachParameters();
        support = new CitySupportService(registry, directory, mapping, parameters);
        support.verifyGroupInvariants();
    }

    @Test
    void officialVilleDirectoryHolds191Cities() {
        assertThat(directory.count()).isEqualTo(191);
        assertThat(directory.findById("1")).hasValueSatisfying(ville ->
                assertThat(ville.habousArabicName()).isEqualTo("الرباط"));
    }

    @Test
    void applicationRegistryHolds67Cities() {
        assertThat(registry.count()).isEqualTo(67);
        assertThat(registry.all()).allSatisfy(city ->
                assertThat(city.zone().getId()).isEqualTo("Africa/Casablanca"));
    }

    @Test
    void verifiedMappingsHold60Cities() {
        assertThat(mapping.count()).isEqualTo(60);
    }

    @Test
    void secApproachHolds48CitiesWithSixPrayerOffsets() {
        assertThat(parameters.count()).isEqualTo(48);
        assertThat(parameters.parameterVersion()).isEqualTo("morocco-habous-secaproach-v2");
        assertThat(parameters.supportedCitySlugs()).allSatisfy(slug ->
                assertThat(parameters.offsetsFor(slug)).hasValueSatisfying(offsets ->
                        assertThat(offsets).hasSize(6)));
    }

    @Test
    void groupsAreStrictlyNested() {
        assertThat(parameters.supportedCitySlugs())
                .allSatisfy(slug -> assertThat(mapping.findByApplicationCityId(slug)).isPresent());
        assertThat(mapping.all()).allSatisfy(m -> {
            assertThat(registry.findBySlug(m.applicationCityId())).isPresent();
            assertThat(directory.isOfficialVille(m.habousCityId())).isTrue();
        });
    }

    @Test
    void neitherThe191NorThe67AreDescribedAsSecApproachSupported() {
        assertThat(support.secApproachCitySlugs()).hasSize(48);
        assertThat(support.collectableVilles()).hasSize(191);

        // The gap is real and must stay visible: 19 registry cities cannot be
        // served by sec-approach, and 131 official villes can never be compared.
        long registryWithoutSecApproach = registry.all().stream()
                .filter(city -> !parameters.isSupported(city.slug()))
                .count();
        assertThat(registryWithoutSecApproach).isEqualTo(19);

        long unmappedVilles = directory.all().stream()
                .filter(ville -> mapping.findByHabousCityId(ville.habousCityId()).isEmpty())
                .count();
        assertThat(unmappedVilles).isEqualTo(131);
    }

    @Test
    void supportLevelDistinguishesAllFourGroups() {
        assertThat(support.supportFor("casablanca").level()).isEqualTo(SupportLevel.SEC_APPROACH_AND_OFFICIAL);
        assertThat(support.supportFor("casablanca").secApproachSupported()).isTrue();

        // Mapped, but never trained: official data only, and it must say so.
        assertThat(support.supportFor("berkane").level()).isEqualTo(SupportLevel.OFFICIAL_ONLY);
        assertThat(support.supportFor("berkane").secApproachSupported()).isFalse();

        // In the registry, no verified ville: no official path at all.
        assertThat(support.supportFor("sale").level()).isEqualTo(SupportLevel.REGISTRY_ONLY);
        assertThat(support.supportFor("sale").habousCityId()).isEmpty();

        assertThat(support.supportFor("nope").level()).isEqualTo(SupportLevel.UNKNOWN);
    }

    @Test
    void theTwelveOfficialOnlyCitiesAreExactlyTheMappedCitiesWithoutOffsets() {
        List<String> officialOnly = mapping.all().stream()
                .map(ApplicationHabousMapping.Mapping::applicationCityId)
                .filter(slug -> !parameters.isSupported(slug))
                .sorted()
                .toList();
        assertThat(officialOnly).containsExactly(
                "berkane", "chefchaouen", "el-jadida", "fkih-ben-salah", "khenifra", "khouribga",
                "ksar-el-kebir", "larache", "midelt", "sidi-ifni", "sidi-kacem", "taroudant");
    }

    @Test
    void unknownCityIsNeverSilentlyDefaulted() {
        assertThat(registry.findBySlug("atlantis")).isEmpty();
        assertThat(parameters.offsetsFor("atlantis")).isEmpty();
        assertThat(parameters.isSupported("atlantis")).isFalse();
    }
}
