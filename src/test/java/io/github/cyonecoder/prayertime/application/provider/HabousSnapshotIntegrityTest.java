package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Snapshot-integrity guard: invalid official data (empty, partial, wrong city,
 * non-contiguous, incoherent, missing provenance) must be rejected on load and
 * must never replace a previously-valid snapshot via {@link OfficialHabousStore#offer}.
 */
class HabousSnapshotIntegrityTest {

    private final OfficialSnapshotValidator validator = new OfficialSnapshotValidator();

    private static PrayerTimes day(LocalDate d) {
        return new PrayerTimes(d,
                LocalTime.of(4, 38), LocalTime.of(6, 23), LocalTime.of(13, 41),
                LocalTime.of(17, 20), LocalTime.of(20, 49), LocalTime.of(22, 19));
    }

    private static OfficialSnapshot snapshot(String city, String habousId, int days) {
        Map<LocalDate, PrayerTimes> map = new LinkedHashMap<>();
        LocalDate start = LocalDate.parse("2026-07-01");
        for (int i = 0; i < days; i++) {
            map.put(start.plusDays(i), day(start.plusDays(i)));
        }
        return new OfficialSnapshot(city, habousId, "checksum", "habous-live-1", Instant.now(), map);
    }

    @Test
    void aValidSnapshotIsAccepted() {
        assertThat(validator.validate(snapshot("casablanca", "58", 5), "casablanca", "58").valid())
                .isTrue();
    }

    @Test
    void emptySnapshotIsRejected() {
        OfficialSnapshot empty = new OfficialSnapshot(
                "casablanca", "58", "c", "p", Instant.now(), Map.of());
        var r = validator.validate(empty, "casablanca", "58");
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("no dates"));
    }

    @Test
    void wrongCityMappingIsRejected() {
        var r = validator.validate(snapshot("casablanca", "999", 5), "casablanca", "58");
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("mapping mismatch"));
    }

    @Test
    void nonContiguousCoverageIsRejected() {
        Map<LocalDate, PrayerTimes> gap = new LinkedHashMap<>();
        gap.put(LocalDate.parse("2026-07-01"), day(LocalDate.parse("2026-07-01")));
        gap.put(LocalDate.parse("2026-07-03"), day(LocalDate.parse("2026-07-03")));
        var r = validator.validate(
                new OfficialSnapshot("casablanca", "58", "c", "p", Instant.now(), gap),
                "casablanca", "58");
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("non-contiguous"));
    }

    @Test
    void incoherentTimesAreRejected() {
        LocalDate d = LocalDate.parse("2026-07-01");
        PrayerTimes scrambled = new PrayerTimes(d,
                LocalTime.of(20, 0), LocalTime.of(6, 0), LocalTime.of(13, 0),
                LocalTime.of(17, 0), LocalTime.of(19, 0), LocalTime.of(22, 0));
        var r = validator.validate(
                new OfficialSnapshot("casablanca", "58", "c", "p", Instant.now(), Map.of(d, scrambled)),
                "casablanca", "58");
        assertThat(r.valid()).isFalse();
        assertThat(r.problems()).anyMatch(p -> p.contains("strictly increasing"));
    }

    @Test
    void missingProvenanceIsRejected() {
        var r = validator.validate(
                new OfficialSnapshot("casablanca", "58", "  ", "  ", Instant.now(),
                        Map.of(LocalDate.parse("2026-07-01"), day(LocalDate.parse("2026-07-01")))),
                "casablanca", "58");
        assertThat(r.valid()).isFalse();
        assertThat(r.problems())
                .anyMatch(p -> p.contains("checksum"))
                .anyMatch(p -> p.contains("parser version"));
    }

    @Test
    void invalidOfferDoesNotReplacePreviousValidSnapshot() {
        OfficialHabousStore store = new OfficialHabousStore(
                HabousCityMapping.load(), validator);
        PrayerTimes before = store.find("casablanca", LocalDate.parse("2026-07-07")).orElseThrow();

        // Offer garbage: empty snapshot for casablanca.
        var result = store.offer(new OfficialSnapshot(
                "casablanca", "58", "c", "p", Instant.now(), Map.of()));
        assertThat(result.valid()).isFalse();

        // Previous valid snapshot preserved unchanged.
        PrayerTimes after = store.find("casablanca", LocalDate.parse("2026-07-07")).orElseThrow();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void officialResultExposesFetchedAtForStalenessJudgement() {
        ProvidedTimes r = new HabousOfficialPrayerTimesProvider()
                .official("casablanca", LocalDate.parse("2026-07-07"), PrayerSourceMode.OFFICIAL)
                .orElseThrow();
        assertThat(r.fetchedAt()).as("fetchedAt lets callers detect stale official data").isNotNull();
    }
}
