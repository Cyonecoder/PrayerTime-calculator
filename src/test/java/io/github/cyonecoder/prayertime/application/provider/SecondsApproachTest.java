package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The sec-approach (per-city, per-prayer seconds offset + floor) must materially
 * beat the global formula against the committed official data.
 */
class SecondsApproachTest {

    private final OfficialHabousStore store = new OfficialHabousStore();
    private final SecondsApproachPrayerTimesProvider sec = new SecondsApproachPrayerTimesProvider();
    private final AstronomicalPrayerTimesProvider global = new AstronomicalPrayerTimesProvider();

    private double exactRatio(boolean useSecApproach) {
        int exact = 0;
        int n = 0;
        for (String cityId : store.supportedCityIds()) {
            OfficialSnapshot s = store.snapshot(cityId).orElseThrow();
            for (LocalDate date : s.dates()) {
                PrayerTimes off = s.day(date).orElseThrow();
                PrayerTimes calc = useSecApproach
                        ? sec.computeTimes(cityId, date) : global.computeTimes(cityId, date);
                for (Prayer p : Prayer.values()) {
                    if (OfficialSnapshot.timeFor(off, p).equals(OfficialSnapshot.timeFor(calc, p))) {
                        exact++;
                    }
                    n++;
                }
            }
        }
        return (double) exact / n;
    }

    @Test
    void secApproachReachesItsAccuracyBandAndBeatsTheGlobalFormula() {
        double secExact = exactRatio(true);
        double globalExact = exactRatio(false);
        System.out.printf("[sec-approach] exact=%.2f%%  (global=%.2f%%)%n",
                100 * secExact, 100 * globalExact);
        assertThat(secExact).isGreaterThanOrEqualTo(0.95);
        assertThat(secExact).isGreaterThan(globalExact + 0.10);
    }

    @Test
    void everyMappedCityIsSupported() {
        for (String cityId : store.supportedCityIds()) {
            assertThat(sec.supports(cityId)).as(cityId).isTrue();
        }
    }

    @Test
    void unsupportedCityFailsClosed() {
        assertThatThrownBy(() -> sec.computeTimes("atlantis", LocalDate.parse("2026-07-07")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secondPreciseTimeFloorsToThePublishedMinute() {
        LocalDate date = LocalDate.parse("2026-07-07");
        for (Prayer p : Prayer.values()) {
            var raw = sec.secondPreciseTime("casablanca", date, p);
            var published = OfficialSnapshot.timeFor(sec.computeTimes("casablanca", date), p);
            // Published minute == floor(raw to the minute).
            assertThat(published).isEqualTo(raw.withSecond(0).withNano(0));
        }
    }
}
