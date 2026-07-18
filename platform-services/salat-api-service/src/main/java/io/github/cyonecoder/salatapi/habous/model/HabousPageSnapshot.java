package io.github.cyonecoder.salatapi.habous.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The provenance of one fetched official page — what {@code metadata.json} holds
 * beside the raw source.
 *
 * <p>The identity is the <strong>official ville id</strong> (spec 13): it is
 * present for every one of the official cities, whereas {@code applicationCityId}
 * is null for the 131 villes with no verified application mapping. Those cities
 * are collected and archived like any other; they simply cannot be compared
 * against sec-approach.
 *
 * @param habousCityId        the official ville id — the stable archive identity
 * @param officialNameAr      the exact Arabic name as published
 * @param applicationCityId   the application slug, or {@code null} for official-only cities
 * @param secApproachSupported whether this city has accepted sec-approach offsets
 * @param fetchedOn           the day the page was fetched (the raw path's date)
 * @param fetchedAt           the instant the page was fetched
 * @param sourceUrl           the exact URL fetched
 * @param sourceChecksum      SHA-256 of the raw HTML
 * @param parserVersion       the parser that produced {@code parsed-days.json}
 * @param dayCount            how many day rows the page yielded
 * @param firstDate           the earliest Gregorian date on the page
 * @param lastDate            the latest Gregorian date on the page
 * @param gregorianMonths     the Gregorian months the page contributes to, e.g. ["2026-06", "2026-07"]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HabousPageSnapshot(
        String habousCityId,
        String officialNameAr,
        String applicationCityId,
        boolean secApproachSupported,
        LocalDate fetchedOn,
        Instant fetchedAt,
        String sourceUrl,
        String sourceChecksum,
        String parserVersion,
        int dayCount,
        LocalDate firstDate,
        LocalDate lastDate,
        List<String> gregorianMonths) {

    public HabousPageSnapshot {
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(fetchedOn, "fetchedOn");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(sourceChecksum, "sourceChecksum");
        gregorianMonths = gregorianMonths == null ? List.of() : List.copyOf(gregorianMonths);
    }
}
