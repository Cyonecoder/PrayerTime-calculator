package io.github.cyonecoder.salatapi.verification.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.cyonecoder.salatapi.common.model.Prayer;
import java.time.LocalTime;

/**
 * The difference between the official and sec-approach time for one prayer.
 *
 * <p>{@code deltaMinutes} is signed ({@code secApproach - official}); {@code exact}
 * is the ~97%-agreement metric — true only when both agree to the minute.
 *
 * @param prayer          the prayer compared
 * @param official        the officially published time
 * @param secApproach     the sec-approach computed time
 * @param deltaMinutes    secApproach minus official, in whole minutes
 * @param exact           whether they agree exactly
 */
public record PrayerDifference(
        Prayer prayer,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime official,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime secApproach,
        int deltaMinutes,
        boolean exact) {

    /** @return the difference between two same-day times, in minutes of day. */
    public static PrayerDifference of(Prayer prayer, LocalTime official, LocalTime secApproach) {
        int delta = secApproach.toSecondOfDay() / 60 - official.toSecondOfDay() / 60;
        return new PrayerDifference(prayer, official, secApproach, delta, delta == 0);
    }
}
