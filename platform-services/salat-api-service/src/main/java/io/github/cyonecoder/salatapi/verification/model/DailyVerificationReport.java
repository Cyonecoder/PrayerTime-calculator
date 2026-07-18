package io.github.cyonecoder.salatapi.verification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The sec-approach-vs-official comparison for one date across the verification
 * intersection (spec 21, 22).
 *
 * <p>The intersection is the sec-approach cities (the trained 48) that also have
 * official data for the date. Every difference is
 * {@code secApproachMinute - officialMinute}. Metrics are reported overall, by
 * city and by prayer.
 *
 * <p>{@link #ACCURACY_DISCLAIMER} labels the released accuracy honestly: it was
 * validated on a summer window and is not an annual claim.
 *
 * @param date              the date compared
 * @param parameterVersion  the sec-approach parameter version under test
 * @param accuracyDisclaimer the honest scope of the released accuracy
 * @param generatedAt       when the comparison ran
 * @param comparedCityCount cities in the intersection (had both sources)
 * @param exactCityCount    cities where all six prayers agreed to the minute
 * @param overall           metrics over every city×prayer comparison
 * @param metricsByCity     metrics per application city slug
 * @param metricsByPrayer   metrics per prayer
 * @param officialChecksums the source checksums the official rows came from
 * @param cities            the per-city comparisons
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyVerificationReport(
        LocalDate date,
        String parameterVersion,
        String accuracyDisclaimer,
        Instant generatedAt,
        int comparedCityCount,
        int exactCityCount,
        VerificationMetrics overall,
        Map<String, VerificationMetrics> metricsByCity,
        Map<String, VerificationMetrics> metricsByPrayer,
        List<String> officialChecksums,
        List<CityDailyVerification> cities) {

    /** The honest scope of the released accuracy; never presented as an annual figure. */
    public static final String ACCURACY_DISCLAIMER =
            "Validated on the 2026-06-17 through 2026-07-16 summer window. "
                    + "Not an annual accuracy claim.";

    public DailyVerificationReport {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(generatedAt, "generatedAt");
        metricsByCity = metricsByCity == null ? Map.of() : Map.copyOf(metricsByCity);
        metricsByPrayer = metricsByPrayer == null ? Map.of() : Map.copyOf(metricsByPrayer);
        officialChecksums = officialChecksums == null ? List.of() : List.copyOf(officialChecksums);
        cities = cities == null ? List.of() : List.copyOf(cities);
    }

    /**
     * @param date              the date
     * @param parameterVersion  the sec-approach parameter version
     * @param generatedAt       when the comparison ran
     * @param cities            the per-city comparisons
     * @param officialChecksums the source checksums the official rows came from
     * @return the report with all metrics computed
     */
    public static DailyVerificationReport of(
            LocalDate date, String parameterVersion, Instant generatedAt,
            List<CityDailyVerification> cities, List<String> officialChecksums) {

        List<Integer> allDiffs = new ArrayList<>();
        Map<String, List<Integer>> byCity = new LinkedHashMap<>();
        Map<Prayer, List<Integer>> byPrayer = new TreeMap<>();
        int exactCities = 0;

        for (CityDailyVerification city : cities) {
            if (city.exactMatch()) {
                exactCities++;
            }
            List<Integer> cityDiffs = new ArrayList<>();
            city.differences().forEach(diff -> {
                allDiffs.add(diff.deltaMinutes());
                cityDiffs.add(diff.deltaMinutes());
                byPrayer.computeIfAbsent(diff.prayer(), k -> new ArrayList<>()).add(diff.deltaMinutes());
            });
            byCity.put(city.applicationCityId(), cityDiffs);
        }

        Map<String, VerificationMetrics> metricsByCity = new LinkedHashMap<>();
        byCity.forEach((slug, diffs) -> metricsByCity.put(slug, VerificationMetrics.over(diffs)));
        Map<String, VerificationMetrics> metricsByPrayer = new LinkedHashMap<>();
        byPrayer.forEach((prayer, diffs) -> metricsByPrayer.put(prayer.key(), VerificationMetrics.over(diffs)));

        return new DailyVerificationReport(
                date, parameterVersion, ACCURACY_DISCLAIMER, generatedAt,
                cities.size(), exactCities,
                VerificationMetrics.over(allDiffs), metricsByCity, metricsByPrayer,
                officialChecksums, cities);
    }
}
