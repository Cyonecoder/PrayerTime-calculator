package io.github.cyonecoder.salatapi.habous.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One city in the runtime catalogue, with full provenance (spec 12).
 *
 * <p>An entry is <strong>never deleted</strong>. When a city disappears from the
 * official dropdown it is marked {@code present = false} and keeps its
 * {@code lastSeenAt}, so historical data collected under that id stays
 * explicable. Renames are likewise additive: the old label moves into
 * {@code previousNames} rather than being overwritten and lost.
 *
 * @param habousCityId       the numeric official ville id
 * @param exactArabicName    the label exactly as published, never altered
 * @param normalizedArabicName the conservatively normalized label (NFC, no tatweel/controls)
 * @param sourceChecksum     SHA-256 of the page this entry was last observed on
 * @param discoveredAt       when this id was first ever seen
 * @param lastSeenAt         when this id was last seen in the official dropdown
 * @param present            whether it appeared in the most recent refresh
 * @param previousNames      every earlier exact label, oldest first; empty if never renamed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CityCatalogueEntry(
        String habousCityId,
        String exactArabicName,
        String normalizedArabicName,
        String sourceChecksum,
        Instant discoveredAt,
        Instant lastSeenAt,
        boolean present,
        List<String> previousNames) {

    public CityCatalogueEntry {
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(exactArabicName, "exactArabicName");
        Objects.requireNonNull(normalizedArabicName, "normalizedArabicName");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        previousNames = previousNames == null ? List.of() : List.copyOf(previousNames);
    }

    /** @return this entry seen again now under {@code exactName}, recording a rename if it changed */
    public CityCatalogueEntry seenAgain(String exactName, String normalized, String checksum, Instant at) {
        boolean renamed = !exactArabicName.equals(exactName);
        List<String> history = renamed
                ? concat(previousNames, exactArabicName)
                : previousNames;
        return new CityCatalogueEntry(
                habousCityId, exactName, normalized, checksum, discoveredAt, at, true, history);
    }

    /** @return this entry marked absent from the latest refresh, retaining all history */
    public CityCatalogueEntry markedAbsent() {
        return new CityCatalogueEntry(
                habousCityId, exactArabicName, normalizedArabicName, sourceChecksum,
                discoveredAt, lastSeenAt, false, previousNames);
    }

    private static List<String> concat(List<String> existing, String extra) {
        return java.util.stream.Stream.concat(existing.stream(), java.util.stream.Stream.of(extra)).toList();
    }
}
