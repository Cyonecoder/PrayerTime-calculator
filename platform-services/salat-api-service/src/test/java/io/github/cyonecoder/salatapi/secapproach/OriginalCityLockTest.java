package io.github.cyonecoder.salatapi.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The 14 original sec-approach cities must stay byte-identical to the retired
 * base14 (v1) parameter file (spec 28).
 *
 * <p>v2 extended coverage from 14 to 48 cities; the original 14 were meant to be
 * carried over unchanged. This test pins that: if a retrain ever perturbs one of
 * the original offsets, the build fails. The base14 file is bundled as a test
 * fixture only — it is never on the runtime classpath.
 */
class OriginalCityLockTest {

    private static final String BASE14 = "/fixtures/secapproach/v1-base14.json";
    private static final String V2 = "/secapproach/morocco-habous-secaproach.json";

    private static final List<String> ORIGINAL_FOURTEEN = List.of(
            "agadir", "casablanca", "dakhla", "errachidia", "fez", "kenitra", "laayoune",
            "marrakech", "meknes", "ouarzazate", "oujda", "rabat", "tangier", "tetouan");

    @Test
    void theFourteenOriginalOffsetsAreByteIdenticalToBase14() throws Exception {
        JsonNode base14 = offsets(BASE14);
        JsonNode v2 = offsets(V2);

        // Sanity: base14 holds exactly the 14 originals.
        List<String> base14Cities = new ArrayList<>();
        base14.fieldNames().forEachRemaining(base14Cities::add);
        assertThat(base14Cities).containsExactlyInAnyOrderElementsOf(ORIGINAL_FOURTEEN);

        // Each original city's six offsets must match v2 exactly.
        for (String city : ORIGINAL_FOURTEEN) {
            assertThat(v2.has(city)).as("v2 contains original city %s", city).isTrue();
            assertThat(v2.get(city))
                    .as("offsets for %s unchanged from base14", city)
                    .isEqualTo(base14.get(city));
        }
    }

    @Test
    void base14IsTheRetiredV1FileAndV2IsThe48CitySet() throws Exception {
        assertThat(root(BASE14).get("parameterVersion").asText()).isEqualTo("morocco-habous-secaproach-v1");
        assertThat(offsets(BASE14).size()).isEqualTo(14);
        assertThat(root(V2).get("parameterVersion").asText()).isEqualTo("morocco-habous-secaproach-v2");
        assertThat(offsets(V2).size()).isEqualTo(48);
    }

    private static JsonNode offsets(String resource) throws Exception {
        return root(resource).get("offsets");
    }

    private static JsonNode root(String resource) throws Exception {
        try (InputStream in = OriginalCityLockTest.class.getResourceAsStream(resource)) {
            return new ObjectMapper().readTree(Objects.requireNonNull(in, "missing " + resource));
        }
    }
}
