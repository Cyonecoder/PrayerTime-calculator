package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bounded deterministic offset search: sign, magnitude, boundaries, rejection. */
class SecApproachOffsetSearchTest {

    private static TrainingObservation obs(String city, LocalDate date, Prayer p, long raw, int min) {
        return new TrainingObservation(city, date, p, raw, min, "UNASSIGNED", "sha");
    }

    /** {@code days} identical observations at {@code raw} raw seconds, official minute {@code min}. */
    private static List<TrainingObservation> constant(long raw, int min, int days) {
        List<TrainingObservation> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            out.add(obs("x", LocalDate.of(2026, 6, 17).plusDays(i), Prayer.DHUHR, raw, min));
        }
        return out;
    }

    @Test
    void findsPositiveOffset() {
        // raw 12:59:30, official 13:00 -> needs +30s.
        SecApproachOffsetSearch.Result r = SecApproachOffsetSearch.search(constant(13 * 3600 - 30, 780, 30));
        assertThat(r.best().offset()).isEqualTo(30);
        assertThat(r.best().exactCount()).isEqualTo(30);
    }

    @Test
    void findsNegativeOffset() {
        // raw 13:01:30 (minute 781), official 13:00 -> offsets [-90,-31] floor to 780;
        // the tie-break keeps the smallest magnitude, -31.
        SecApproachOffsetSearch.Result r = SecApproachOffsetSearch.search(constant(13 * 3600 + 90, 780, 30));
        assertThat(r.best().offset()).isEqualTo(-31);
        assertThat(r.best().exactCount()).isEqualTo(30);
    }

    @Test
    void findsOffsetLargerThanSixtySeconds() {
        // raw 12:58:00 (minute 778), official 13:00 (minute 780) -> needs +120s.
        SecApproachOffsetSearch.Result r = SecApproachOffsetSearch.search(constant(13 * 3600 - 120, 780, 30));
        assertThat(r.best().offset()).isEqualTo(120);
        assertThat(Math.abs(r.best().offset())).isGreaterThan(60);
        assertThat(r.best().exactCount()).isEqualTo(30);
    }

    @Test
    void handlesTheFiftyNineToSixtySecondBoundary() {
        // raw 13:00:00: offset 59 stays in minute 780; offset 60 crosses to 781.
        assertThat(SecApproachEvaluator.predictedMinute(13 * 3600, 59)).isEqualTo(780);
        assertThat(SecApproachEvaluator.predictedMinute(13 * 3600, 60)).isEqualTo(781);
        // raw 13:00:01: offset 59 crosses to 781 (01+59=60s -> next minute).
        assertThat(SecApproachEvaluator.predictedMinute(13 * 3600 + 1, 58)).isEqualTo(780);
        assertThat(SecApproachEvaluator.predictedMinute(13 * 3600 + 1, 59)).isEqualTo(781);
    }

    @Test
    void flagsBoundaryWhenTheWinnerIsOnTheEdge() {
        // Only +5s reaches minute 781 within [-5,5].
        SecApproachOffsetSearch.Result r =
                SecApproachOffsetSearch.search(constant(13 * 3600 + 55, 781, 1), -5, 5);
        assertThat(r.best().offset()).isEqualTo(5);
        assertThat(r.touchesBoundary()).isTrue();
    }

    @Test
    void rejectsIncompleteData() {
        assertThatThrownBy(() -> SecApproachOffsetSearch.search(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void rejectsDuplicateObservations() {
        LocalDate d = LocalDate.of(2026, 6, 17);
        List<TrainingObservation> dup = List.of(
                obs("x", d, Prayer.DHUHR, 46800, 780), obs("x", d, Prayer.DHUHR, 46800, 780));
        assertThatThrownBy(() -> SecApproachOffsetSearch.search(dup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsMixedCityData() {
        List<TrainingObservation> mixed = List.of(
                obs("a", LocalDate.of(2026, 6, 17), Prayer.DHUHR, 46800, 780),
                obs("b", LocalDate.of(2026, 6, 18), Prayer.DHUHR, 46800, 780));
        assertThatThrownBy(() -> SecApproachOffsetSearch.search(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixed-city");
    }

    @Test
    void rejectsWrongPrayerData() {
        List<TrainingObservation> mixed = List.of(
                obs("a", LocalDate.of(2026, 6, 17), Prayer.DHUHR, 46800, 780),
                obs("a", LocalDate.of(2026, 6, 18), Prayer.ASR, 46800, 780));
        assertThatThrownBy(() -> SecApproachOffsetSearch.search(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixed-prayer");
    }
}
