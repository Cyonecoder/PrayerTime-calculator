package io.github.cyonecoder.salatapi.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.secapproach.calculation.HabousRawEngine;
import io.github.cyonecoder.salatapi.secapproach.configuration.ReconstructedMethod;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import io.github.cyonecoder.salatapi.secapproach.service.SecApproachCalculator;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Parity with the source branch at <strong>both</strong> precisions and across a
 * timezone transition (spec 28).
 *
 * <p>The fixture {@code golden-vectors-v2-precision.csv} was generated from the
 * original {@code SecondsApproachPrayerTimesProvider} (source commit {@code 01ee8d4})
 * over all 48 released cities and all six events, at minute <em>and</em> second
 * precision, on dates that straddle the Ramadan clock change in
 * {@code Africa/Casablanca} (offset {@code +01 ↔ +00}) and include a leap day. The
 * standalone engine must reproduce every value exactly, at both precisions.
 */
class SecApproachPrecisionParityTest {

    private static final String GOLDEN = "/fixtures/secapproach/golden-vectors-v2-precision.csv";

    private record Vector(String city, LocalDate date, Prayer prayer, LocalTime minute, LocalTime second) {}

    private static SecApproachCalculator calculator;
    private static List<Vector> vectors;

    @BeforeAll
    static void setUp() {
        calculator = new SecApproachCalculator(
                new MoroccoCityRegistry(), new HabousRawEngine(),
                ReconstructedMethod.frozen(), new SecApproachParameters());
        vectors = load();
    }

    @Test
    void minuteAndSecondPrecisionBothMatchTheSourceBranchExactly() {
        List<String> mismatches = new ArrayList<>();
        // Compute one schedule per city/date at each precision, then check all six.
        var byKey = new java.util.LinkedHashMap<String, List<Vector>>();
        for (Vector v : vectors) {
            byKey.computeIfAbsent(v.city() + "|" + v.date(), k -> new ArrayList<>()).add(v);
        }
        byKey.forEach((key, group) -> {
            Vector first = group.get(0);
            PrayerTimes minute = calculator.computeTimes(first.city(), first.date());
            PrayerTimes second = calculator.computeSecondPreciseTimes(first.city(), first.date());
            for (Vector v : group) {
                if (!minute.get(v.prayer()).equals(v.minute())) {
                    mismatches.add(v.city() + " " + v.date() + " " + v.prayer()
                            + " minute: expected " + v.minute() + " got " + minute.get(v.prayer()));
                }
                if (!second.get(v.prayer()).equals(v.second())) {
                    mismatches.add(v.city() + " " + v.date() + " " + v.prayer()
                            + " second: expected " + v.second() + " got " + second.get(v.prayer()));
                }
            }
        });

        assertThat(mismatches).as("standalone must match the source branch at both precisions").isEmpty();
        assertThat(vectors).hasSize(2304);   // 48 cities × 8 dates × 6 prayers
    }

    @Test
    void theFixtureSpansAllForty8CitiesTheTimezoneTransitionAndALeapDay() {
        assertThat(vectors.stream().map(Vector::city).distinct().toList()).hasSize(48);
        assertThat(vectors.stream().map(Vector::date).distinct().toList())
                .contains(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 23), LocalDate.of(2028, 2, 29));
    }

    private static List<Vector> load() {
        List<Vector> loaded = new ArrayList<>();
        try (InputStream in = SecApproachPrecisionParityTest.class.getResourceAsStream(GOLDEN);
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        Objects.requireNonNull(in, "missing " + GOLDEN), StandardCharsets.UTF_8))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] c = line.split(",");
                loaded.add(new Vector(c[0], LocalDate.parse(c[1]),
                        Prayer.fromKey(c[2]).orElseThrow(), LocalTime.parse(c[3]), LocalTime.parse(c[4])));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return List.copyOf(loaded);
    }
}
