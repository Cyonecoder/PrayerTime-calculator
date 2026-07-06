package io.github.cyonecoder.prayertime.validation;

import java.util.Objects;

/**
 * A single validation problem found in a set of prayer times.
 *
 * @param code    a stable, machine-readable code (e.g. {@code "ORDERING"})
 * @param message a human-readable description of the problem
 */
public record ValidationIssue(String code, String message) {

    public ValidationIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    static ValidationIssue of(String code, String message) {
        return new ValidationIssue(code, message);
    }
}
