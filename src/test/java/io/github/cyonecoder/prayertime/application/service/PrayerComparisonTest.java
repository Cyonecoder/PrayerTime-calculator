package io.github.cyonecoder.prayertime.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class PrayerComparisonTest {

    @Test
    void zeroDifferenceIsWithinTolerance() {
        PrayerComparison c = PrayerComparison.of(
                Prayer.DHUHR, LocalTime.of(12, 0), LocalTime.of(12, 0), 2);
        assertThat(c.signedDifferenceMinutes()).isZero();
        assertThat(c.absoluteDifferenceMinutes()).isZero();
        assertThat(c.withinTolerance()).isTrue();
    }

    @Test
    void positiveDifferenceWhenCalculatedIsLater() {
        PrayerComparison c = PrayerComparison.of(
                Prayer.FAJR, LocalTime.of(5, 3), LocalTime.of(5, 0), 2);
        assertThat(c.signedDifferenceMinutes()).isEqualTo(3);
        assertThat(c.absoluteDifferenceMinutes()).isEqualTo(3);
        assertThat(c.withinTolerance()).isFalse();
    }

    @Test
    void negativeDifferenceWhenCalculatedIsEarlier() {
        PrayerComparison c = PrayerComparison.of(
                Prayer.FAJR, LocalTime.of(4, 57), LocalTime.of(5, 0), 2);
        assertThat(c.signedDifferenceMinutes()).isEqualTo(-3);
        assertThat(c.absoluteDifferenceMinutes()).isEqualTo(3);
    }

    @Test
    void differenceIsMidnightSafe() {
        // 00:05 vs 23:55 is +10 minutes, not ~-1430.
        PrayerComparison across = PrayerComparison.of(
                Prayer.ISHA, LocalTime.of(0, 5), LocalTime.of(23, 55), 2);
        assertThat(across.signedDifferenceMinutes()).isEqualTo(10);
        assertThat(across.absoluteDifferenceMinutes()).isEqualTo(10);

        PrayerComparison reversed = PrayerComparison.of(
                Prayer.ISHA, LocalTime.of(23, 55), LocalTime.of(0, 5), 2);
        assertThat(reversed.signedDifferenceMinutes()).isEqualTo(-10);
    }

    @Test
    void tolerancePassesAtBoundaryAndFailsBeyond() {
        assertThat(PrayerComparison.of(Prayer.ASR, LocalTime.of(17, 2), LocalTime.of(17, 0), 2)
                .withinTolerance()).isTrue();
        assertThat(PrayerComparison.of(Prayer.ASR, LocalTime.of(17, 3), LocalTime.of(17, 0), 2)
                .withinTolerance()).isFalse();
    }
}
