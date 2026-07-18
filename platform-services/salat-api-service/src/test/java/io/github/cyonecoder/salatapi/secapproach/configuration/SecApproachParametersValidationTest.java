package io.github.cyonecoder.salatapi.secapproach.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Startup validation of the sec-approach parameter resource (spec 7).
 *
 * <p>The released v2 parameters and the retired v1 file sit next to each other in
 * the source branch under near-identical names, so loading the wrong one is a
 * realistic mistake rather than a hypothetical. These tests prove the guard
 * actually fires: a wrong version or city count must stop the application, not
 * degrade it silently to 14-city coverage.
 */
class SecApproachParametersValidationTest {

    @Test
    void bundledResourceIsTheReleasedV2ParameterSet() {
        SecApproachParameters parameters = new SecApproachParameters();

        assertThat(parameters.parameterVersion()).isEqualTo("morocco-habous-secaproach-v2");
        assertThat(parameters.count()).isEqualTo(48);
        assertThat(parameters.datasetChecksum())
                .isEqualTo("78a417aa01af75d31d3013ced940b7611fec5e28e8f6d082a27e53eba906f8c9");
    }

    @Test
    void theRetiredV1ParameterFileIsRejected() {
        assertThatThrownBy(() -> new SecApproachParameters("/fixtures/secapproach/v1-base14.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("morocco-habous-secaproach-v2")
                .hasMessageContaining("morocco-habous-secaproach-v1");
    }

    @Test
    void aMissingResourceIsRejectedRatherThanTreatedAsEmpty() {
        assertThatThrownBy(() -> new SecApproachParameters("/secapproach/does-not-exist.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void anUnsupportedCityYieldsNoOffsetsRatherThanZeroOffsets() {
        SecApproachParameters parameters = new SecApproachParameters();

        // 'sale' is a registry city with no verified mapping and no offsets. Falling
        // back to zero offsets would publish an unvalidated time as if it were v2.
        assertThat(parameters.isSupported("sale")).isFalse();
        assertThat(parameters.offsetsFor("sale")).isEmpty();
    }
}
