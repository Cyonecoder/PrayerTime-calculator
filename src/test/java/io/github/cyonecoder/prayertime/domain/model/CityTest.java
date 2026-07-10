package io.github.cyonecoder.prayertime.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CityTest {

    private static final Coordinates COORDS = new Coordinates(34.0361152, -6.7480954, 96);
    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");

    @Test
    void constructsWithHabousId() {
        City city = new City("casablanca", "Casablanca", COORDS, ZONE, Optional.of("101"));

        assertThat(city.id()).isEqualTo("casablanca");
        assertThat(city.displayName()).isEqualTo("Casablanca");
        assertThat(city.coordinates()).isEqualTo(COORDS);
        assertThat(city.zone()).isEqualTo(ZONE);
        assertThat(city.habousCityId()).contains("101");
    }

    @Test
    void factoryWithoutHabousIdLeavesItEmpty() {
        City city = City.of("rabat", "Rabat", COORDS, ZONE);

        assertThat(city.habousCityId()).isEmpty();
    }

    @Test
    void rejectsNullComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new City(null, "Casablanca", COORDS, ZONE, Optional.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new City("casablanca", null, COORDS, ZONE, Optional.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new City("casablanca", "Casablanca", null, ZONE, Optional.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new City("casablanca", "Casablanca", COORDS, null, Optional.empty()));
        assertThatNullPointerException()
                .isThrownBy(() -> new City("casablanca", "Casablanca", COORDS, ZONE, null));
    }

    @Test
    void isImmutableRecord() {
        assertThat(City.class.isRecord()).isTrue();
    }
}
