package io.github.cyonecoder.salatapi.habous.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * What changed between the previously-known official city catalogue and a fresh
 * observation (spec 12).
 *
 * <p>All five change kinds are reported rather than acted on silently. A removal
 * in particular never deletes anything: it marks the entry absent and is surfaced
 * here so an operator can see that the official source dropped a city.
 *
 * @param newCities        ids present now but never seen before
 * @param removedCities    ids known before but absent from this observation
 * @param renamedCities    ids whose exact Arabic label changed
 * @param duplicateIds     ids appearing more than once in a single observation
 * @param duplicateLabels  exact labels shared by more than one id in one observation
 */
public record CatalogueDiff(
        List<String> newCities,
        List<String> removedCities,
        List<Rename> renamedCities,
        List<String> duplicateIds,
        List<DuplicateLabel> duplicateLabels) {

    /**
     * @param habousCityId the ville id whose label changed
     * @param previousName the label previously published
     * @param currentName  the label now published
     */
    public record Rename(String habousCityId, String previousName, String currentName) {}

    /**
     * @param exactArabicName the label published for more than one id
     * @param habousCityIds   the ids sharing it
     */
    public record DuplicateLabel(String exactArabicName, List<String> habousCityIds) {}

    public CatalogueDiff {
        newCities = newCities == null ? List.of() : List.copyOf(newCities);
        removedCities = removedCities == null ? List.of() : List.copyOf(removedCities);
        renamedCities = renamedCities == null ? List.of() : List.copyOf(renamedCities);
        duplicateIds = duplicateIds == null ? List.of() : List.copyOf(duplicateIds);
        duplicateLabels = duplicateLabels == null ? List.of() : List.copyOf(duplicateLabels);
    }

    /** @return a diff reporting no change at all */
    public static CatalogueDiff unchanged() {
        return new CatalogueDiff(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** @return whether the official source differs from what was previously known */
    @JsonIgnore
    public boolean hasChanges() {
        return !newCities.isEmpty()
                || !removedCities.isEmpty()
                || !renamedCities.isEmpty()
                || !duplicateIds.isEmpty()
                || !duplicateLabels.isEmpty();
    }

    /**
     * @return whether the observation contains internal inconsistencies. Duplicates
     *         are not a normal evolution of the source — they suggest a malformed
     *         page or an upstream data error, and mapping by id or by label becomes
     *         ambiguous while they persist.
     */
    @JsonIgnore
    public boolean hasAnomalies() {
        return !duplicateIds.isEmpty() || !duplicateLabels.isEmpty();
    }
}
