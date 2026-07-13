package io.github.cyonecoder.prayertime.audit.release;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityMapping;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.audit.release.CorrectionsLayer.CityCorrection;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.Group;
import io.github.cyonecoder.prayertime.audit.release.HabousMethodValidator.ValidationResult;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prompt-6 release-validation tests. No metric or fixture is bent to pass. */
class ReleaseValidationTest {

    private static final Path DATASET = Path.of("research-data/habous");

    private HabousMethodValidator validator() {
        return new HabousMethodValidator(
                new HabousRawEngine(), new CityLocations(), HabousCityMapping.load());
    }

    private ValidationResult validateReal(CorrectionsLayer layer) {
        return validator().validate(ReconstructedMethod.ID, ReconstructedMethod.frozen(),
                ReferenceDataset.load(DATASET), layer);
    }

    private static CorrectionsLayer empty() {
        return new CorrectionsLayer(List.of(), List.of());
    }

    @Test
    void exactMatchCountIsDeterministicAndComplete() {
        ValidationResult a = validateReal(empty());
        ValidationResult b = validateReal(empty());
        var ga = a.group(Group.FORMULA_ONLY);
        var gb = b.group(Group.FORMULA_ONLY);
        // 14 cities x 30 days x 6 prayers.
        assertThat(ga.total()).isEqualTo(14 * 30 * 6);
        // Deterministic across runs (pins the frozen method + committed data).
        assertThat(gb.exactCount()).isEqualTo(ga.exactCount());
        assertThat(ga.exactCount()).isEqualTo(ga.total() - unresolved(ga.comparisons()));
        assertThat(ga.exactCount()).isBetween(1, ga.total());
    }

    private static int unresolved(List<HabousMethodValidator.Comparison> comparisons) {
        return (int) comparisons.stream().filter(c -> c.signedError() != 0).count();
    }

    @Test
    void elevationDipHorizonImprovesTheFrozenReconstruction() {
        // Regression guard on the Prompt-7 elevation-dip horizon model: the frozen
        // reconstruction reaches its improved accuracy band on the committed data,
        // while still leaving real residuals (so it is never declared exact).
        var g = validateReal(empty()).group(Group.FORMULA_ONLY);
        assertThat(g.exactPercentage()).isGreaterThan(78.0).isLessThan(100.0);
        assertThat(g.withinOnePercentage()).isGreaterThan(98.0);
        assertThat(g.maxAbsolute()).isLessThanOrEqualTo(2);
        // The frozen reconstruction actually carries the fitted dip coefficients.
        assertThat(ReconstructedMethod.frozen().sunriseHorizonDipCoefficient()).isGreaterThan(0.0);
        assertThat(ReconstructedMethod.frozen().maghribHorizonDipCoefficient()).isGreaterThan(0.0);
    }

    @Test
    void unresolvedDifferencesFailOfficialCompatibleValidation() {
        ValidationResult result = validateReal(empty());
        ReleaseDecision decision = ReleaseDecision.evaluate(result);
        // The pure formula leaves real residuals, so the Habous-compatible gate
        // (which demands 100% exact) must fail, and the release is NO-GO.
        assertThat(result.group(Group.FORMULA_PLUS_CORRECTIONS_PLUS_OVERRIDES).exactPercentage())
                .isLessThan(100.0);
        assertThat(decision.habousCompatiblePass()).isFalse();
        assertThat(decision.releaseApproved()).isFalse();
        assertThat(decision.pureFormulaReaches100()).isFalse();
    }

    @Test
    void unapprovedCorrectionsFailTheHabousCompatibleGate() {
        CorrectionsLayer layer = new CorrectionsLayer(
                List.of(new CityCorrection("rabat", Prayer.FAJR, 1, false, null, "sha", "unapproved")),
                List.of());
        ValidationResult result = validateReal(layer);
        ReleaseDecision decision = ReleaseDecision.evaluate(result);
        assertThat(result.unapprovedCorrections()).hasSize(1);
        assertThat(decision.unreviewedCorrections()).isEqualTo(1);
        assertThat(decision.habousCompatibleFailures())
                .anyMatch(f -> f.contains("unreviewed corrections"));
        // An unapproved correction is never applied.
        assertThat(result.group(Group.FORMULA_PLUS_CORRECTIONS).correctionsApplied()).isZero();
    }

    @Test
    void missingDatesFailTheHabousCompatibleGate() {
        // rabat with a deliberate one-day gap (07-02 removed).
        List<Observation> days = new ArrayList<>();
        for (int d : new int[] {1, 3, 4}) {
            days.add(obs("rabat", LocalDate.of(2026, 7, d)));
        }
        ValidationResult result = validator().validate(
                ReconstructedMethod.ID, ReconstructedMethod.frozen(),
                ReferenceDataset.of(days), empty());
        ReleaseDecision decision = ReleaseDecision.evaluate(result);
        assertThat(result.missingDates()).isEqualTo(1);
        assertThat(decision.habousCompatibleFailures()).anyMatch(f -> f.contains("missing dates"));
    }

    @Test
    void changedSourceChecksumsRequireReview() {
        // An approved correction whose recorded source checksum is stale: it must
        // be flagged as needing review and must NOT be applied.
        CorrectionsLayer layer = new CorrectionsLayer(
                List.of(new CityCorrection("rabat", Prayer.FAJR, 1, true, "me", "STALE", "drifted")),
                List.of());
        ValidationResult result = validateReal(layer);
        ReleaseDecision decision = ReleaseDecision.evaluate(result);
        assertThat(result.staleCorrections()).hasSize(1);
        assertThat(decision.habousCompatibleFailures())
                .anyMatch(f -> f.contains("changed source checksums"));
        assertThat(result.group(Group.FORMULA_PLUS_CORRECTIONS).correctionsApplied()).isZero();
    }

    @Test
    void reportsAreDeterministic(@TempDir Path a, @TempDir Path b) throws Exception {
        ValidationResult r1 = validateReal(empty());
        ValidationResult r2 = validateReal(empty());
        ReleaseReportWriter.Provenance fixed = new ReleaseReportWriter.Provenance(
                "2026-07-07T00:00:00Z", "fixed-commit", "note-c", "note-o");
        new ReleaseReportWriter().write(a, r1, ReleaseDecision.evaluate(r1), fixed);
        new ReleaseReportWriter().write(b, r2, ReleaseDecision.evaluate(r2), fixed);
        for (String file : ReleaseReportWriter.FILES) {
            assertThat(Files.readString(a.resolve(file)))
                    .as("deterministic: %s", file)
                    .isEqualTo(Files.readString(b.resolve(file)));
        }
    }

    @Test
    void allMappedCitiesAreIncluded() {
        ValidationResult result = validateReal(empty());
        // Every city validated here is a verified Habous mapping (the mapping also
        // carries research-verified cities beyond this 14-city release dataset).
        assertThat(HabousCityMapping.load().verifiedCityIds())
                .containsAll(result.includedCities());
        assertThat(result.missingMappedCities()).isEmpty();
        assertThat(result.includedCities()).hasSize(14);
    }

    private static Observation obs(String city, LocalDate date) {
        return new Observation(city, date,
                LocalTime.of(4, 30), LocalTime.of(6, 18), LocalTime.of(13, 37),
                LocalTime.of(17, 17), LocalTime.of(20, 47), LocalTime.of(22, 19));
    }
}
