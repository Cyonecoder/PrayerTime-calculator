package io.github.cyonecoder.prayertime.validation;

import io.github.cyonecoder.prayertime.application.service.PrayerComparison;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Validates a set of prayer times for internal consistency and, optionally,
 * against astronomical reference points and comparison tolerances.
 *
 * <p>Each method returns the list of {@link ValidationIssue}s found; an empty
 * list means the input passed all checks.
 */
public final class PrayerTimesValidator {

    /**
     * Structural checks that depend only on the times themselves:
     * strict chronological ordering, Fajr before Sunrise, Asr strictly between
     * Dhuhr and Maghrib, and Isha after Maghrib.
     *
     * @param times the times to validate (non-null; its components are already
     *              guaranteed non-null by {@link PrayerTimes})
     * @return the issues found, empty if valid
     */
    public List<ValidationIssue> validate(PrayerTimes times) {
        Objects.requireNonNull(times, "times");
        List<ValidationIssue> issues = new ArrayList<>();

        Prayer[] order = {
                Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA
        };
        for (int i = 0; i < order.length - 1; i++) {
            LocalTime earlier = times.get(order[i]);
            LocalTime later = times.get(order[i + 1]);
            if (!earlier.isBefore(later)) {
                issues.add(ValidationIssue.of("ORDERING",
                        order[i] + " (" + earlier + ") must be before " + order[i + 1]
                                + " (" + later + ")"));
            }
        }

        requireBefore(issues, "FAJR_BEFORE_SUNRISE", Prayer.FAJR, times.fajr(), Prayer.SUNRISE, times.sunrise());
        requireBefore(issues, "ASR_AFTER_DHUHR", Prayer.DHUHR, times.dhuhr(), Prayer.ASR, times.asr());
        requireBefore(issues, "ASR_BEFORE_MAGHRIB", Prayer.ASR, times.asr(), Prayer.MAGHRIB, times.maghrib());
        requireBefore(issues, "ISHA_AFTER_MAGHRIB", Prayer.MAGHRIB, times.maghrib(), Prayer.ISHA, times.isha());
        return issues;
    }

    /**
     * All structural checks plus astronomical relationships: Dhuhr must fall
     * after solar noon and Maghrib after sunset.
     *
     * @param times     the times to validate
     * @param solarNoon the computed solar noon
     * @param sunset    the computed sunset
     * @return the issues found, empty if valid
     */
    public List<ValidationIssue> validate(PrayerTimes times, LocalTime solarNoon, LocalTime sunset) {
        Objects.requireNonNull(solarNoon, "solarNoon");
        Objects.requireNonNull(sunset, "sunset");
        List<ValidationIssue> issues = validate(times);

        if (!solarNoon.isBefore(times.dhuhr())) {
            issues.add(ValidationIssue.of("DHUHR_AFTER_SOLAR_NOON",
                    "Dhuhr (" + times.dhuhr() + ") must be after solar noon (" + solarNoon + ")"));
        }
        if (!sunset.isBefore(times.maghrib())) {
            issues.add(ValidationIssue.of("MAGHRIB_AFTER_SUNSET",
                    "Maghrib (" + times.maghrib() + ") must be after sunset (" + sunset + ")"));
        }
        return issues;
    }

    /**
     * Flags any comparison whose absolute difference exceeds its tolerance.
     *
     * @param comparisons the per-prayer comparisons
     * @return an issue per out-of-tolerance prayer, empty if all pass
     */
    public List<ValidationIssue> validateTolerances(Collection<PrayerComparison> comparisons) {
        Objects.requireNonNull(comparisons, "comparisons");
        List<ValidationIssue> issues = new ArrayList<>();
        for (PrayerComparison c : comparisons) {
            if (!c.withinTolerance()) {
                issues.add(ValidationIssue.of("TOLERANCE",
                        c.prayer() + " differs by " + c.absoluteDifferenceMinutes()
                                + " min (signed " + c.signedDifferenceMinutes()
                                + "), outside tolerance"));
            }
        }
        return issues;
    }

    private void requireBefore(
            List<ValidationIssue> issues, String code,
            Prayer earlierPrayer, LocalTime earlier, Prayer laterPrayer, LocalTime later) {
        if (!earlier.isBefore(later)) {
            issues.add(ValidationIssue.of(code,
                    earlierPrayer + " (" + earlier + ") must be before " + laterPrayer
                            + " (" + later + ")"));
        }
    }
}
