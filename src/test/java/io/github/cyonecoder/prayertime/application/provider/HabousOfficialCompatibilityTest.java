package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every committed official reference row must be reproduced exactly by
 * {@code OFFICIAL} mode: application official output == Habous HH:mm. Any mismatch
 * fails the build. This is the permanent exact-compatibility guarantee.
 */
class HabousOfficialCompatibilityTest {

    private final OfficialHabousStore store = new OfficialHabousStore();
    private final HabousCompatiblePrayerTimesProvider provider =
            new HabousCompatiblePrayerTimesProvider(
                    new HabousOfficialPrayerTimesProvider(store),
                    new AstronomicalPrayerTimesProvider());

    @Test
    void officialModeReproducesEveryCommittedRowExactly() {
        int rows = 0;
        List<String> mismatches = new ArrayList<>();
        for (String cityId : store.supportedCityIds()) {
            OfficialSnapshot snapshot = store.snapshot(cityId).orElseThrow();
            for (LocalDate date : snapshot.dates()) {
                PrayerTimes expected = snapshot.day(date).orElseThrow();
                PrayerTimes actual = provider.get(cityId, date, PrayerSourceMode.OFFICIAL).times();
                for (Prayer p : Prayer.values()) {
                    if (!OfficialSnapshot.timeFor(expected, p).equals(OfficialSnapshot.timeFor(actual, p))) {
                        mismatches.add(cityId + " " + date + " " + p + ": expected "
                                + OfficialSnapshot.timeFor(expected, p) + " got "
                                + OfficialSnapshot.timeFor(actual, p));
                    }
                    rows++;
                }
            }
        }
        assertThat(rows).as("official rows compared").isGreaterThan(0);
        assertThat(mismatches).as("official-mode exact mismatches").isEmpty();
    }

    @Test
    void officialValuesAreNeverRoundedOrModified() {
        // The served official time is byte-identical to the stored snapshot value:
        // no rounding pass, no correction, no recomputation.
        ProvidedTimes r = provider.get("casablanca", LocalDate.parse("2026-07-07"),
                PrayerSourceMode.OFFICIAL);
        PrayerTimes stored = store.find("casablanca", LocalDate.parse("2026-07-07")).orElseThrow();
        assertThat(r.times()).isEqualTo(stored);
        assertThat(r.official()).isTrue();
        assertThat(r.source()).isEqualTo(ProvenanceSource.HABOUS_OFFICIAL);
    }

    @Test
    void officialResultCarriesFullProvenanceMetadata() {
        ProvidedTimes r = provider.get("casablanca", LocalDate.parse("2026-07-07"),
                PrayerSourceMode.OFFICIAL);
        assertThat(r.sourceMode()).isEqualTo(PrayerSourceMode.OFFICIAL);
        assertThat(r.official()).isTrue();
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.habousCityId()).isEqualTo("58");
        assertThat(r.sourceChecksum()).isNotBlank();
        assertThat(r.fetchedAt()).isNotNull();
        assertThat(r.parameterVersion()).isNull();
        assertThat(r.times().fajr()).hasToString("04:38");
        assertThat(r.times().sunrise()).hasToString("06:23");
    }
}
