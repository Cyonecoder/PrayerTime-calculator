package io.github.cyonecoder.prayertime.domain.method;

/**
 * A named prayer-time calculation method together with the parameters that
 * configure it (for example the Moroccan/Habous convention).
 *
 * <p>All method-specific behaviour is expressed through {@link #parameters()};
 * the generic mathematical engine stays free of any method- or country-specific
 * conditions.
 */
public interface CalculationMethod {

    /**
     * @return a stable machine identifier, e.g. {@code "morocco"}
     */
    String id();

    /**
     * @return a human-readable name, e.g. {@code "Morocco (Habous)"}
     */
    String displayName();

    /**
     * @return the astronomical parameters and adjustments for this method
     */
    CalculationParameters parameters();

    /**
     * Convenience alias for {@link #displayName()} used where a single name
     * string is recorded (e.g. in a result's provenance).
     *
     * @return the display name
     */
    default String methodName() {
        return displayName();
    }
}
