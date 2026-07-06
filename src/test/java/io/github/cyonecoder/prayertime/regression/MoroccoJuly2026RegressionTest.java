package io.github.cyonecoder.prayertime.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import io.github.cyonecoder.prayertime.application.service.PrayerComparison;
import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService;
import io.github.cyonecoder.prayertime.application.service.PrayerTimeComparisonService.ComparisonResult;
import io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator;
import io.github.cyonecoder.prayertime.domain.method.MoroccoCalculationMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Coordinates;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerRequest;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimesResult;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import io.github.cyonecoder.prayertime.validation.PrayerTimesValidator;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression suite validating the corrected engine against CACHED official
 * Morocco (Habous) reference values for July 2026 (see
 * {@code fixtures/reference/morocco-july-2026.json}).
 *
 * <p>These are cached reference values, not a live Habous response. The five
 * obligatory prayers must be within 2 minutes; Sunrise is reported separately
 * and does not affect pass/fail.
 */
class MoroccoJuly2026RegressionTest {

    private static final int FIVE_PRAYER_TOLERANCE_MIN = 2;
    private static final ZoneId CASA_ZONE = ZoneId.of("Africa/Casablanca");
    private static final List<Prayer> FIVE =
            List.of(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA);

    private static final MoroccoCityRegistry REGISTRY = new MoroccoCityRegistry();
    private static final PrayerTimeCalculator CALCULATOR = new PrayerTimeCalculator();
    private static final PrayerTimeComparisonService COMPARISON =
            new PrayerTimeComparisonService();
    private static final PrayerTimesValidator VALIDATOR = new PrayerTimesValidator();

    // Accumulated rows for the end-of-run report.
    private static final List<Row> REPORT_ROWS = new ArrayList<>();

    private record ReferenceDay(
            String city, String date,
            String fajr, String sunrise, String dhuhr, String asr, String maghrib, String isha) {

        LocalDate localDate() {
            return LocalDate.parse(date);
        }

        PrayerTimes toPrayerTimes() {
            return new PrayerTimes(localDate(),
                    LocalTime.parse(fajr), LocalTime.parse(sunrise), LocalTime.parse(dhuhr),
                    LocalTime.parse(asr), LocalTime.parse(maghrib), LocalTime.parse(isha));
        }

        @Override
        public String toString() {
            return city + " " + date;
        }
    }

    private record ReferenceFile(List<ReferenceDay> days) {
    }

    private record Row(String city, LocalDate date, Map<Prayer, Long> signed,
                       long sunriseSigned, long maxAbsFive, boolean pass) {
    }

    static Stream<ReferenceDay> referenceDays() {
        try (InputStream in = MoroccoJuly2026RegressionTest.class
                .getResourceAsStream("/fixtures/reference/morocco-july-2026.json");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, ReferenceFile.class).days().stream();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load reference fixture", e);
        }
    }

    @Test
    void fajrUsesThe19DegreeMoroccoMethod() {
        assertThat(new MoroccoCalculationMethod().parameters().fajrAngle()).isEqualTo(19.0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceDays")
    void engineMatchesCachedReferenceWithinTolerance(ReferenceDay reference) {
        City city = REGISTRY.requireById(reference.city());
        Coordinates coordinates = city.coordinates();
        LocalDate date = reference.localDate();

        PrayerRequest request =
                PrayerRequest.of(date, coordinates, CASA_ZONE, new MoroccoCalculationMethod());

        // Requirement 5: the offset used must be +01:00 (summer time in July).
        assertThat(CALCULATOR.offsetFor(request)).isEqualTo(ZoneOffset.ofHours(1));

        PrayerTimes calculated = CALCULATOR.calculate(request);

        // Requirement 6: results remain chronologically ordered.
        assertThat(VALIDATOR.validate(calculated)).isEmpty();

        PrayerTimesResult official = new PrayerTimesResult(
                reference.toPrayerTimes(), "habous-cached-reference", "Habous Official (cached)",
                Instant.parse("2026-07-01T00:00:00Z"), CASA_ZONE, ZoneOffset.ofHours(1));

        ComparisonResult.Compared report = (ComparisonResult.Compared)
                COMPARISON.compare(calculated, "astronomical", Optional.of(official));

        // Record signed differences for the report.
        Map<Prayer, Long> signed = new EnumMap<>(Prayer.class);
        for (PrayerComparison c : report.prayerComparisons()) {
            signed.put(c.prayer(), c.signedDifferenceMinutes());
        }
        REPORT_ROWS.add(new Row(reference.city(), date, signed,
                report.sunriseComparison().signedDifferenceMinutes(),
                report.maxAbsoluteErrorMinutes(), report.allWithinTolerance()));

        // Requirement 3: five-prayer maximum difference is at most 2 minutes.
        assertThat(report.maxAbsoluteErrorMinutes())
                .as("five-prayer max abs error for %s", reference)
                .isLessThanOrEqualTo(FIVE_PRAYER_TOLERANCE_MIN);
        assertThat(report.allWithinTolerance())
                .as("all five within tolerance for %s", reference)
                .isTrue();
    }

    @AfterAll
    static void writeReport() throws Exception {
        if (REPORT_ROWS.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Morocco July 2026 Regression Report\n");
        sb.append("(CACHED reference values — NOT a live Habous response)\n");
        sb.append("Five-prayer tolerance: ").append(FIVE_PRAYER_TOLERANCE_MIN)
                .append(" min. Sunrise reported separately (excluded from pass/fail).\n\n");

        sb.append(String.format("%-11s %-11s %5s %5s %5s %5s %5s | %6s %5s %-4s%n",
                "City", "Date", "Fajr", "Dhuhr", "Asr", "Mgrb", "Isha", "SunR*", "MaxAE", "PASS"));

        // Per-prayer aggregates.
        Map<Prayer, Long> sumAbs = new EnumMap<>(Prayer.class);
        Map<Prayer, Long> maxAbs = new EnumMap<>(Prayer.class);
        FIVE.forEach(p -> {
            sumAbs.put(p, 0L);
            maxAbs.put(p, 0L);
        });
        Map<String, Boolean> perCityPass = new TreeMap<>();
        boolean overallPass = true;

        for (Row row : REPORT_ROWS) {
            sb.append(String.format("%-11s %-11s %+5d %+5d %+5d %+5d %+5d | %+6d %5d %-4s%n",
                    row.city(), row.date(),
                    row.signed().get(Prayer.FAJR), row.signed().get(Prayer.DHUHR),
                    row.signed().get(Prayer.ASR), row.signed().get(Prayer.MAGHRIB),
                    row.signed().get(Prayer.ISHA), row.sunriseSigned(),
                    row.maxAbsFive(), row.pass() ? "yes" : "NO"));
            for (Prayer p : FIVE) {
                long abs = Math.abs(row.signed().get(p));
                sumAbs.merge(p, abs, Long::sum);
                maxAbs.merge(p, abs, Math::max);
            }
            perCityPass.merge(row.city(), row.pass(), Boolean::logicalAnd);
            overallPass = overallPass && row.pass();
        }

        int n = REPORT_ROWS.size();
        sb.append("\nPer-prayer error across all ").append(n).append(" city/date entries:\n");
        for (Prayer p : FIVE) {
            sb.append(String.format("  %-8s MAE=%.2f min  maxAE=%d min%n",
                    p, sumAbs.get(p) / (double) n, maxAbs.get(p)));
        }

        sb.append("\nResult per city:\n");
        perCityPass.forEach((c, pass) ->
                sb.append(String.format("  %-11s %s%n", c, pass ? "PASS" : "FAIL")));

        sb.append("\n* Sunrise is a known systematic +2..+5 min late vs the official table; "
                + "reported here but excluded from pass/fail by design.\n");
        sb.append("\nOVERALL: ").append(overallPass ? "PASS" : "FAIL").append('\n');

        String report = sb.toString();
        System.out.println(report);

        Path out = Path.of("build", "reports", "morocco-july-2026-regression.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
    }
}
