package io.github.cyonecoder.salatapi.verification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The sec-approach-vs-official comparison for one date across the verification
 * intersection (spec 20).
 *
 * <p>The intersection is the sec-approach cities (the trained 48) that also have
 * official data for the date. The 143 official-only and unmapped cities are not
 * compared — collecting official data for a city never makes it comparable.
 *
 * @param date               the date compared
 * @param parameterVersion   the sec-approach parameter version under test
 * @param generatedAt        when the comparison ran
 * @param comparedCityCount  cities in the intersection (had both sources)
 * @param exactMatchCityCount cities where all six prayers agreed to the minute
 * @param exactMatchRate     {@code exactMatchCityCount / comparedCityCount}, 0..1
 * @param cities             the per-city comparisons
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DailyVerificationReport(
        LocalDate date,
        String parameterVersion,
        Instant generatedAt,
        int comparedCityCount,
        int exactMatchCityCount,
        double exactMatchRate,
        List<CityDailyVerification> cities) {

    public DailyVerificationReport {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(generatedAt, "generatedAt");
        cities = cities == null ? List.of() : List.copyOf(cities);
    }

    /**
     * @param date             the date
     * @param parameterVersion the sec-approach parameter version
     * @param generatedAt      when the comparison ran
     * @param cities           the per-city comparisons
     * @return the report with its aggregates computed
     */
    public static DailyVerificationReport of(
            LocalDate date, String parameterVersion, Instant generatedAt,
            List<CityDailyVerification> cities) {
        int compared = cities.size();
        int exact = (int) cities.stream().filter(CityDailyVerification::exactMatch).count();
        double rate = compared == 0 ? 0.0 : (double) exact / compared;
        return new DailyVerificationReport(date, parameterVersion, generatedAt, compared, exact, rate, cities);
    }
}
