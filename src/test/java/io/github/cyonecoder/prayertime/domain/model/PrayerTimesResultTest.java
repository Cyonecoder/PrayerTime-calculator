package io.github.cyonecoder.prayertime.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PrayerTimesResultTest {

    private static final PrayerTimes TIMES = new PrayerTimes(
            LocalDate.of(2024, 3, 17),
            LocalTime.of(5, 7, 23),
            LocalTime.of(6, 31, 36),
            LocalTime.of(12, 41, 1),
            LocalTime.of(16, 0, 59),
            LocalTime.of(18, 41, 55),
            LocalTime.of(19, 55, 15));
    private static final Instant GENERATED_AT = Instant.parse("2026-07-06T10:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(1);

    @Test
    void constructsWithValidValues() {
        PrayerTimesResult result = new PrayerTimesResult(
                TIMES, "astronomical", "Morocco (Habous)", GENERATED_AT, ZONE, OFFSET);

        assertThat(result.prayerTimes()).isEqualTo(TIMES);
        assertThat(result.source()).isEqualTo("astronomical");
        assertThat(result.methodName()).isEqualTo("Morocco (Habous)");
        assertThat(result.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(result.timezone()).isEqualTo(ZONE);
        assertThat(result.utcOffset()).isEqualTo(OFFSET);
    }

    @Test
    void rejectsNullComponents() {
        assertThatNullPointerException().isThrownBy(() ->
                new PrayerTimesResult(null, "astronomical", "m", GENERATED_AT, ZONE, OFFSET));
        assertThatNullPointerException().isThrownBy(() ->
                new PrayerTimesResult(TIMES, null, "m", GENERATED_AT, ZONE, OFFSET));
        assertThatNullPointerException().isThrownBy(() ->
                new PrayerTimesResult(TIMES, "astronomical", null, GENERATED_AT, ZONE, OFFSET));
        assertThatNullPointerException().isThrownBy(() ->
                new PrayerTimesResult(TIMES, "astronomical", "m", null, ZONE, OFFSET));
        assertThatNullPointerException().isThrownBy(() ->
                new PrayerTimesResult(TIMES, "astronomical", "m", GENERATED_AT, null, OFFSET));
        assertThatNullPointerException().isThrownBy(() ->
                new PrayerTimesResult(TIMES, "astronomical", "m", GENERATED_AT, ZONE, null));
    }

    @Test
    void isImmutableRecord() {
        assertThat(PrayerTimesResult.class.isRecord()).isTrue();
    }
}
