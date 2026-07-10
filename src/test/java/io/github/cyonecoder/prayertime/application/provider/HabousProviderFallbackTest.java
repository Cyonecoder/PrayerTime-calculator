package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Source-mode behaviour: calculated, official (no silent fallback), auto, compare. */
class HabousProviderFallbackTest {

    private static final LocalDate COVERED = LocalDate.parse("2026-07-07");
    private static final LocalDate UNCOVERED = LocalDate.parse("2026-01-01");

    private final AstronomicalPrayerTimesProvider calculated = new AstronomicalPrayerTimesProvider();
    private final HabousCompatiblePrayerTimesProvider provider =
            new HabousCompatiblePrayerTimesProvider(
                    new HabousOfficialPrayerTimesProvider(), calculated);

    @Test
    void calculatedModeReturnsReconstructedResult() {
        ProvidedTimes r = provider.get("casablanca", COVERED, PrayerSourceMode.CALCULATED);
        assertThat(r.source()).isEqualTo(ProvenanceSource.ASTRONOMICAL_RECONSTRUCTED);
        assertThat(r.official()).isFalse();
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.parameterVersion()).isEqualTo("morocco-habous-v1");
        // Byte-identical to the standalone reconstructed calculation.
        assertThat(r.times()).isEqualTo(calculated.computeTimes("casablanca", COVERED));
    }

    @Test
    void autoModePrefersOfficialWhenAvailable() {
        ProvidedTimes r = provider.get("casablanca", COVERED, PrayerSourceMode.AUTO);
        assertThat(r.source()).isEqualTo(ProvenanceSource.HABOUS_OFFICIAL);
        assertThat(r.official()).isTrue();
        assertThat(r.fallbackUsed()).isFalse();
        PrayerTimes expected =
                new HabousOfficialPrayerTimesProvider().officialTimes("casablanca", COVERED).orElseThrow();
        assertThat(r.times()).isEqualTo(expected);
    }

    @Test
    void autoModeFallsBackToCalculationWhenOfficialMissing() {
        ProvidedTimes r = provider.get("casablanca", UNCOVERED, PrayerSourceMode.AUTO);
        assertThat(r.source()).isEqualTo(ProvenanceSource.ASTRONOMICAL_RECONSTRUCTED);
        assertThat(r.official()).isFalse();
        assertThat(r.fallbackUsed()).as("fallback must be clearly flagged").isTrue();
        assertThat(r.parameterVersion()).isEqualTo("morocco-habous-v1");
        assertThat(r.times()).isEqualTo(calculated.computeTimes("casablanca", UNCOVERED));
    }

    @Test
    void officialModeNeverFallsBackSilently() {
        assertThatThrownBy(() -> provider.get("casablanca", UNCOVERED, PrayerSourceMode.OFFICIAL))
                .isInstanceOf(HabousDataUnavailableException.class)
                .hasMessageContaining(HabousDataUnavailableException.CODE);
    }

    @Test
    void compareModeExposesBothResultsAndDifferences() {
        ProvidedTimes r = provider.get("casablanca", COVERED, PrayerSourceMode.COMPARE);
        assertThat(r.comparisonOpt()).isPresent();
        ProvidedTimes.Comparison c = r.comparison();
        assertThat(c.officialAvailable()).isTrue();
        assertThat(c.official()).isNotNull();
        assertThat(c.calculated()).isEqualTo(calculated.computeTimes("casablanca", COVERED));
        assertThat(c.differenceMinutes()).containsKeys(Prayer.values());
        // Differences equal official minus calculated for each prayer.
        for (Prayer p : Prayer.values()) {
            int off = min(OfficialSnapshot.timeFor(c.official(), p));
            int calc = min(OfficialSnapshot.timeFor(c.calculated(), p));
            assertThat(c.differenceMinutes().get(p)).isEqualTo(off - calc);
        }
    }

    @Test
    void compareModeStillWorksWithoutOfficialData() {
        ProvidedTimes r = provider.get("casablanca", UNCOVERED, PrayerSourceMode.COMPARE);
        assertThat(r.comparison().officialAvailable()).isFalse();
        assertThat(r.comparison().official()).isNull();
        assertThat(r.comparison().differenceMinutes()).isEmpty();
        assertThat(r.source()).isEqualTo(ProvenanceSource.ASTRONOMICAL_RECONSTRUCTED);
    }

    private static int min(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
