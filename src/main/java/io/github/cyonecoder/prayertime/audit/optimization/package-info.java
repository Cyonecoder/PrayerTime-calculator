/**
 * Research / audit parameter-search engine for the Moroccan (Habous) method.
 *
 * <p>This package is <strong>research-only</strong>. It discovers the astronomical
 * parameters and publication rounding policy that best reproduce official Habous
 * prayer times, without ever changing the production calculation defaults. Nothing
 * here is wired into {@code MoroccoCalculationMethod} or the production
 * {@link io.github.cyonecoder.prayertime.domain.calculation.PrayerTimeCalculator};
 * it only <em>reuses</em> the pure astronomical components to evaluate candidate
 * parameter sets against a reference dataset.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>{@link io.github.cyonecoder.prayertime.audit.optimization.ParameterRange}
 *       generates candidate values with exact decimal-step arithmetic.</li>
 *   <li>{@link io.github.cyonecoder.prayertime.audit.optimization.CalculationCandidate}
 *       is one fully-specified parameter set plus a rounding policy and operation
 *       order.</li>
 *   <li>{@link io.github.cyonecoder.prayertime.audit.optimization.ParameterSearchService}
 *       searches one prayer's parameter independently; the full Cartesian product is
 *       never enumerated.</li>
 *   <li>{@link io.github.cyonecoder.prayertime.audit.optimization.PrayerParameterSearch}
 *       runs the independent per-prayer searches, then a small combined validation
 *       over only the top candidates.</li>
 * </ul>
 */
package io.github.cyonecoder.prayertime.audit.optimization;
