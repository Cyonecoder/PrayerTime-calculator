package io.github.cyonecoder.prayertime.application.provider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The reconstructed formula must stay within its accepted accuracy band against
 * the committed official data. This is a threshold test (not 100%): official
 * exceptions are served by {@code OFFICIAL}/{@code AUTO} mode, not by the formula.
 */
class HabousFormulaAccuracyTest {

    // Accepted band for the frozen MOROCCO_HABOUS_RECONSTRUCTED formula on the
    // committed official rows (see reports/habous-reconstruction-prompt7.md).
    private static final double MIN_EXACT = 0.70;
    private static final double MIN_WITHIN_ONE = 0.95;
    private static final int MAX_ERROR_MINUTES = 2;

    private final OfficialHabousStore store = new OfficialHabousStore();
    private final AstronomicalPrayerTimesProvider calculated = new AstronomicalPrayerTimesProvider();

    @Test
    void reconstructedFormulaStaysWithinItsAcceptedBand() {
        int n = 0;
        int exact = 0;
        int withinOne = 0;
        int maxErr = 0;
        long sumAbs = 0;
        for (String cityId : store.supportedCityIds()) {
            OfficialSnapshot s = store.snapshot(cityId).orElseThrow();
            for (LocalDate date : s.dates()) {
                PrayerTimes off = s.day(date).orElseThrow();
                PrayerTimes calc = calculated.computeTimes(cityId, date);
                for (Prayer p : Prayer.values()) {
                    int d = Math.abs(min(OfficialSnapshot.timeFor(off, p))
                            - min(OfficialSnapshot.timeFor(calc, p)));
                    if (d == 0) {
                        exact++;
                    }
                    if (d <= 1) {
                        withinOne++;
                    }
                    maxErr = Math.max(maxErr, d);
                    sumAbs += d;
                    n++;
                }
            }
        }
        double exactPct = (double) exact / n;
        double withinPct = (double) withinOne / n;
        double mae = (double) sumAbs / n;
        System.out.printf(
                "[formula] n=%d exact=%.2f%% within1=%.2f%% MAE=%.3f maxErr=%d%n",
                n, 100 * exactPct, 100 * withinPct, mae, maxErr);

        assertThat(exactPct).as("exact-match ratio").isGreaterThanOrEqualTo(MIN_EXACT);
        assertThat(withinPct).as("within-1-minute ratio").isGreaterThanOrEqualTo(MIN_WITHIN_ONE);
        assertThat(maxErr).as("maximum error (minutes)").isLessThanOrEqualTo(MAX_ERROR_MINUTES);
    }

    private static int min(java.time.LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
