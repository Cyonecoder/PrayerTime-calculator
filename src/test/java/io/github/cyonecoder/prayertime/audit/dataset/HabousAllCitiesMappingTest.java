package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline invariants of the application-to-Habous mapping. Never touches the network. */
class HabousAllCitiesMappingTest {

    private final HabousCityMapping mapping = HabousCityMapping.load();
    private final SecondsApproachPrayerTimesProvider sec = new SecondsApproachPrayerTimesProvider();

    private List<String> committedSecCities() {
        try (InputStream in = getClass().getResourceAsStream(
                "/research/morocco-habous-secaproach.json")) {
            JsonObject root = new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            return new ArrayList<>(root.getAsJsonObject("offsets").keySet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void everyAcceptedSecCityHasAVerifiedMapping() {
        List<String> verified = mapping.verifiedCityIds();
        for (String city : committedSecCities()) {
            assertThat(sec.supports(city)).as("%s supported", city).isTrue();
            assertThat(verified).as("%s verified mapping", city).contains(city);
        }
    }

    @Test
    void everyMappingHasANumericHabousIdAndArabicName() {
        for (HabousCityMapping.Entry e : mapping.all()) {
            assertThat(e.habousCityId()).as("%s id", e.applicationCityId()).matches("\\d+");
            assertThat(e.habousArabicName()).as("%s arabic", e.applicationCityId()).isNotBlank();
        }
    }

    @Test
    void noTwoApplicationCitiesShareAHabousId() {
        List<String> ids = mapping.all().stream().map(HabousCityMapping.Entry::habousCityId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void unverifiedCandidatesAreNotTrainedAndGetNoGuessedMapping() {
        // These have no dedicated Habous page (NOT_LISTED); they must be neither
        // mapped nor servable — never guessed.
        for (String city : List.of("temara", "tamesna", "sale", "kabila", "bouskoura")) {
            assertThat(mapping.forCity(city)).as("%s unmapped", city).isEmpty();
            assertThat(sec.supports(city)).as("%s fails closed", city).isFalse();
        }
    }
}
