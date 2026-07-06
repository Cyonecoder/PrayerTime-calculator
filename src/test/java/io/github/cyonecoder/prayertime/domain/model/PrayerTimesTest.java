package io.github.cyonecoder.prayertime.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class PrayerTimesTest {

    private static final LocalDate DATE = LocalDate.of(2024, 3, 17);
    private static final LocalTime FAJR = LocalTime.of(5, 7, 23);
    private static final LocalTime SUNRISE = LocalTime.of(6, 31, 36);
    private static final LocalTime DHUHR = LocalTime.of(12, 41, 1);
    private static final LocalTime ASR = LocalTime.of(16, 0, 59);
    private static final LocalTime MAGHRIB = LocalTime.of(18, 41, 55);
    private static final LocalTime ISHA = LocalTime.of(19, 55, 15);

    private static PrayerTimes sample() {
        return new PrayerTimes(DATE, FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA);
    }

    @Test
    void constructsWithValidValues() {
        PrayerTimes times = sample();

        assertThat(times.date()).isEqualTo(DATE);
        assertThat(times.fajr()).isEqualTo(FAJR);
        assertThat(times.isha()).isEqualTo(ISHA);
    }

    @Test
    void lookupReturnsCorrectTimePerPrayer() {
        PrayerTimes times = sample();

        assertThat(times.get(Prayer.FAJR)).isEqualTo(FAJR);
        assertThat(times.get(Prayer.SUNRISE)).isEqualTo(SUNRISE);
        assertThat(times.get(Prayer.DHUHR)).isEqualTo(DHUHR);
        assertThat(times.get(Prayer.ASR)).isEqualTo(ASR);
        assertThat(times.get(Prayer.MAGHRIB)).isEqualTo(MAGHRIB);
        assertThat(times.get(Prayer.ISHA)).isEqualTo(ISHA);
    }

    @Test
    void lookupRejectsNullPrayer() {
        assertThatNullPointerException().isThrownBy(() -> sample().get(null));
    }

    @Test
    void rejectsNullComponents() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(null, FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(DATE, null, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(DATE, FAJR, null, DHUHR, ASR, MAGHRIB, ISHA));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(DATE, FAJR, SUNRISE, null, ASR, MAGHRIB, ISHA));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(DATE, FAJR, SUNRISE, DHUHR, null, MAGHRIB, ISHA));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(DATE, FAJR, SUNRISE, DHUHR, ASR, null, ISHA));
        assertThatNullPointerException()
                .isThrownBy(() -> new PrayerTimes(DATE, FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, null));
    }

    @Test
    void isImmutableRecordAndCoversEveryPrayer() {
        assertThat(PrayerTimes.class.isRecord()).isTrue();
        // Every enum constant must resolve to a value (guards the switch).
        for (Prayer prayer : Prayer.values()) {
            assertThat(sample().get(prayer)).isNotNull();
        }
    }
}
