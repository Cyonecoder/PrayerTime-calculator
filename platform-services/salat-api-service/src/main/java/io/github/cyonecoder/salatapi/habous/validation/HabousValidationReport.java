package io.github.cyonecoder.salatapi.habous.validation;

import java.util.List;

/**
 * The outcome of validating a fetched official page (spec 20).
 *
 * @param valid    whether the page may be stored and archived
 * @param problems human-readable reasons it was rejected; empty when valid
 */
public record HabousValidationReport(boolean valid, List<String> problems) {

    public HabousValidationReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** @return a passing report */
    public static HabousValidationReport ok() {
        return new HabousValidationReport(true, List.of());
    }

    /** @param problems why the page was rejected */
    public static HabousValidationReport rejected(List<String> problems) {
        return new HabousValidationReport(false, problems);
    }
}
