package io.github.cyonecoder.salatapi.habous.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The runtime catalogue of official Habous cities (spec 12): every ville ever
 * observed, with the ones present in the latest refresh flagged.
 *
 * <p>The count here is <strong>observed, not assumed</strong>. It starts equal to
 * the bundled 191-city bootstrap index but may legitimately differ once the
 * official source changes, and the API must report {@link #observedCount()}
 * rather than a hard-coded 191.
 *
 * @param refreshedAt    when the latest successful refresh happened
 * @param sourceChecksum SHA-256 of the page the latest refresh parsed
 * @param parserVersion  the parser that produced it
 * @param entries        every city ever seen, including absent ones
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CityCatalogue(
        Instant refreshedAt,
        String sourceChecksum,
        String parserVersion,
        List<CityCatalogueEntry> entries) {

    public CityCatalogue {
        Objects.requireNonNull(refreshedAt, "refreshedAt");
        Objects.requireNonNull(parserVersion, "parserVersion");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** @return the cities currently published by the official source */
    @JsonIgnore
    public List<CityCatalogueEntry> presentEntries() {
        return entries.stream().filter(CityCatalogueEntry::present).toList();
    }

    /**
     * @return the number of cities the official source currently publishes — the
     *         value the API must report. Never hard-code 191.
     */
    @JsonIgnore
    public int observedCount() {
        return presentEntries().size();
    }

    /** @return cities seen historically but absent from the latest refresh; never deleted */
    @JsonIgnore
    public List<CityCatalogueEntry> absentEntries() {
        return entries.stream().filter(e -> !e.present()).toList();
    }

    /** @param habousCityId a numeric ville id */
    public Optional<CityCatalogueEntry> byId(String habousCityId) {
        if (habousCityId == null) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(e -> e.habousCityId().equals(habousCityId.trim()))
                .findFirst();
    }

    /** @return the ville ids eligible for official collection right now */
    @JsonIgnore
    public List<String> collectableVilleIds() {
        return presentEntries().stream().map(CityCatalogueEntry::habousCityId).toList();
    }
}
