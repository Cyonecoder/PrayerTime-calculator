package io.github.cyonecoder.prayertime.domain.method;

/**
 * A named prayer-time calculation method (for example the Moroccan/Habous
 * convention).
 *
 * <p>This is intentionally minimal at this stage: it only exposes a
 * human-readable name so that domain models such as
 * {@link io.github.cyonecoder.prayertime.domain.model.PrayerRequest} can refer
 * to a method without depending on any calculation logic. The astronomical
 * parameters and behaviour are added in a later step.
 */
public interface CalculationMethod {

    /**
     * @return a stable, human-readable name for this method,
     *         e.g. {@code "Morocco (Habous)"}.
     */
    String methodName();
}
