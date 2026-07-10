package io.github.cyonecoder.prayertime.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CoordinatesTest {

    @Test
    void constructsWithValidValues() {
        Coordinates casablanca = new Coordinates(34.0361152, -6.7480954, 96);

        assertThat(casablanca.latitude()).isEqualTo(34.0361152);
        assertThat(casablanca.longitude()).isEqualTo(-6.7480954);
        assertThat(casablanca.elevationMeters()).isEqualTo(96);
    }

    @Test
    void acceptsExtremeButValidLatitudeAndLongitude() {
        assertThat(new Coordinates(90, 180, 0)).isNotNull();
        assertThat(new Coordinates(-90, -180, 0)).isNotNull();
    }

    @Test
    void allowsZeroAndNegativeElevation() {
        assertThat(new Coordinates(0, 0, 0).elevationMeters()).isZero();
        assertThat(new Coordinates(0, 0, -430.5).elevationMeters()).isEqualTo(-430.5);
    }

    @Test
    void rejectsLatitudeAboveNinety() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(90.1, 0, 0))
                .withMessageContaining("latitude");
    }

    @Test
    void rejectsLatitudeBelowMinusNinety() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(-90.1, 0, 0))
                .withMessageContaining("latitude");
    }

    @Test
    void rejectsLongitudeAboveOneEighty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(0, 180.1, 0))
                .withMessageContaining("longitude");
    }

    @Test
    void rejectsLongitudeBelowMinusOneEighty() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(0, -180.1, 0))
                .withMessageContaining("longitude");
    }

    @Test
    void rejectsNaN() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Coordinates(Double.NaN, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Coordinates(0, Double.NaN, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Coordinates(0, 0, Double.NaN));
    }

    @Test
    void rejectsInfinity() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(Double.POSITIVE_INFINITY, 0, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(0, Double.NEGATIVE_INFINITY, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Coordinates(0, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void isImmutableRecord() {
        assertThat(Coordinates.class.isRecord()).isTrue();
    }
}
