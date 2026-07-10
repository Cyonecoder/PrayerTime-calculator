package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Source-change monitoring: a candidate that differs from the accepted snapshot
 * produces a review artifact (added/removed/changed) and requires manual
 * approval — it never auto-updates and never changes the accepted snapshot.
 */
class HabousSnapshotReviewTest {

    private final HabousSnapshotReview review = new HabousSnapshotReview();

    private static PrayerTimes t(LocalDate d, int fajrMin) {
        return new PrayerTimes(d,
                LocalTime.of(4, fajrMin), LocalTime.of(6, 23), LocalTime.of(13, 41),
                LocalTime.of(17, 20), LocalTime.of(20, 49), LocalTime.of(22, 19));
    }

    private static OfficialSnapshot snap(String checksum, Map<LocalDate, PrayerTimes> days) {
        return new OfficialSnapshot("casablanca", "58", checksum, "habous-live-1", Instant.now(), days);
    }

    @Test
    void identicalCandidateNeedsNoApproval() {
        LocalDate d = LocalDate.parse("2026-07-01");
        Map<LocalDate, PrayerTimes> days = Map.of(d, t(d, 38));
        var r = review.review(snap("a", days), snap("a", days));
        assertThat(r.identical()).isTrue();
        assertThat(r.requiresApproval()).isFalse();
    }

    @Test
    void changedCandidateProducesReviewAndRequiresApproval() {
        LocalDate d1 = LocalDate.parse("2026-07-01");
        LocalDate d2 = LocalDate.parse("2026-07-02");
        Map<LocalDate, PrayerTimes> accepted = new LinkedHashMap<>();
        accepted.put(d1, t(d1, 38));
        accepted.put(d2, t(d2, 38));
        Map<LocalDate, PrayerTimes> candidate = new LinkedHashMap<>();
        candidate.put(d1, t(d1, 39)); // fajr changed
        candidate.put(LocalDate.parse("2026-07-03"), t(LocalDate.parse("2026-07-03"), 38)); // added; d2 removed

        var r = review.review(snap("old", accepted), snap("new", candidate));
        assertThat(r.requiresApproval()).isTrue();
        assertThat(r.addedDates()).containsExactly(LocalDate.parse("2026-07-03"));
        assertThat(r.removedDates()).containsExactly(d2);
        assertThat(r.changedCells()).anySatisfy(c -> {
            assertThat(c.date()).isEqualTo(d1);
            assertThat(c.prayer()).isEqualTo(Prayer.FAJR);
            assertThat(c.accepted()).isEqualTo("04:38");
            assertThat(c.candidate()).isEqualTo("04:39");
        });
        assertThat(review.render(r)).contains("manual approval required");
    }
}
