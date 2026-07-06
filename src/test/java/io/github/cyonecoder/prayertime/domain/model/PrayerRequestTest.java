package io.github.cyonecoder.prayertime.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.cyonecoder.prayertime.domain.method.CalculationMethod;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class PrayerRequestTest {

    private static final LocalDate DATE = LocalDate.of(2024, 3, 17);
    private static final Coordinates COORDS = new Coordinates(34.0361152, -6.7480954, 96);
    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private static final CalculationMethod METHOD = () -> "Test Method";

    @Test
    void factoryWithoutOverrideUsesCoordinateElevation() {
        PrayerRequest request = PrayerRequest.of(DATE, COORDS, ZONE, METHOD);

        assertThat(request.elevationOverrideMeters()).isEmpty();
        assertThat(request.effectiveElevationMeters()).isEqualTo(96);
        assertThat(request.method().methodName()).isEqualTo("Test Method");
    }

    @Test
    void overrideReplacesCoordinateElevation() {
        PrayerRequest request =
                new PrayerRequest(DATE, COORDS, ZONE, METHOD, OptionalDouble.of(0));

        assertThat(request.effectiveElevationMeters()).isEqualTo(0);
    }

    @Test
    void rejectsNullComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerRequest(null, COORDS, ZONE, METHOD, OptionalDouble.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerRequest(DATE, null, ZONE, METHOD, OptionalDouble.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerRequest(DATE, COORDS, null, METHOD, OptionalDouble.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerRequest(DATE, COORDS, ZONE, null, OptionalDouble.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerRequest(DATE, COORDS, ZONE, METHOD, null));
    }

    @Test
    void isImmutableRecord() {
        assertThat(PrayerRequest.class.isRecord()).isTrue();
    }
}
