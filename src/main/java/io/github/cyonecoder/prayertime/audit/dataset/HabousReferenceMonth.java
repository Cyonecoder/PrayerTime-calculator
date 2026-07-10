package io.github.cyonecoder.prayertime.audit.dataset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * A normalized, immutable snapshot of one city's official Habous schedule for a
 * single Gregorian month, with full provenance.
 *
 * @param applicationCityId the application city slug (never a redesigned model)
 * @param habousCityId      the external Habous {@code ville} identifier
 * @param habousArabicName  the Arabic city name preserved from the source
 * @param year              the Gregorian year
 * @param month             the Gregorian month (1..12)
 * @param fetchedAt         when the source was retrieved
 * @param sourceUrl         the URL the source HTML was fetched from
 * @param sourceChecksum    the SHA-256 hex checksum of the source HTML
 * @param parserVersion     the parser version used to normalize the source
 * @param dailyRecords      the per-day records, one per Gregorian day present
 */
public record HabousReferenceMonth(
        String applicationCityId,
        String habousCityId,
        String habousArabicName,
        int year,
        int month,
        Instant fetchedAt,
        String sourceUrl,
        String sourceChecksum,
        String parserVersion,
        List<HabousReferenceDay> dailyRecords) {

    public HabousReferenceMonth {
        Objects.requireNonNull(applicationCityId, "applicationCityId");
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(habousArabicName, "habousArabicName");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(sourceChecksum, "sourceChecksum");
        Objects.requireNonNull(parserVersion, "parserVersion");
        Objects.requireNonNull(dailyRecords, "dailyRecords");
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be 1..12 but was " + month);
        }
        if (dailyRecords.isEmpty()) {
            throw new IllegalArgumentException("dailyRecords must not be empty");
        }
        YearMonth ym = YearMonth.of(year, month);
        TreeSet<LocalDate> seen = new TreeSet<>();
        for (HabousReferenceDay day : dailyRecords) {
            if (day.date().getYear() != year || day.date().getMonthValue() != month) {
                throw new IllegalArgumentException(
                        "day " + day.date() + " is outside " + ym);
            }
            if (!seen.add(day.date())) {
                throw new IllegalArgumentException("duplicate day: " + day.date());
            }
        }
        dailyRecords = List.copyOf(dailyRecords);
    }

    /** @return the {@link YearMonth} of this snapshot. */
    public YearMonth yearMonth() {
        return YearMonth.of(year, month);
    }

    /** @return {@code true} if every calendar day of the month is present. */
    public boolean isComplete() {
        return dailyRecords.size() == yearMonth().lengthOfMonth();
    }

    /**
     * The calendar days of the month that have no record.
     *
     * @return the missing dates, ascending (empty when complete)
     */
    public List<LocalDate> missingDays() {
        TreeSet<LocalDate> present = new TreeSet<>();
        for (HabousReferenceDay day : dailyRecords) {
            present.add(day.date());
        }
        List<LocalDate> missing = new ArrayList<>();
        YearMonth ym = yearMonth();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = ym.atDay(d);
            if (!present.contains(date)) {
                missing.add(date);
            }
        }
        return missing;
    }
}
