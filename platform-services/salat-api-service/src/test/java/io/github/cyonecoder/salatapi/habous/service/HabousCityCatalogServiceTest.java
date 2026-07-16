package io.github.cyonecoder.salatapi.habous.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.model.CatalogueDiff;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogue;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.parser.HabousParseException;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The daily official city catalogue refresh (spec 12).
 *
 * <p>The catalogue is the service's only defence against the official source
 * changing under it. These tests cover each detectable change, and the rule that
 * matters most: historical city data is never silently deleted.
 */
class HabousCityCatalogServiceTest {

    @TempDir
    Path dataDir;

    private HabousCityCatalogService service;
    private SalatApiProperties properties;
    private Clock clock;

    private static String pageWith(String... options) {
        StringBuilder sb = new StringBuilder("<html><body><form><select name=\"ville\">");
        sb.append("<option value=\"\">اختر مدينة</option>"); // placeholder, must be skipped
        for (String option : options) {
            sb.append(option);
        }
        return sb.append("</select></form></body></html>").toString();
    }

    private static String option(String id, String arabicName) {
        return "<option value=\"index.php?ville=" + id + "\">" + arabicName + "</option>";
    }

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC);
        properties = new SalatApiProperties(dataDir);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new HabousCityCatalogService(
                new OfficialHabousCityDirectory(),
                new HabousVilleDirectoryParser(new Sha256Service()),
                new AtomicJsonFileWriter(mapper),
                properties,
                clock);
    }

    @Test
    void beforeAnyRefreshTheCatalogueBootstrapsFromTheBundled191() {
        assertThat(service.observedCount()).isEqualTo(191);
        assertThat(service.collectableVilleIds()).hasSize(191);
        assertThat(service.findById("1")).hasValueSatisfying(entry -> {
            assertThat(entry.exactArabicName()).isEqualTo("الرباط");
            assertThat(entry.present()).isTrue();
        });
    }

    @Test
    void aRefreshPreservesIdLabelNormalizedLabelChecksumAndDiscoveryTimestamp() {
        String html = pageWith(option("1", "الرباط"), option("117", "أكادير"));

        var result = service.refresh(html);

        assertThat(result.catalogue().byId("1")).hasValueSatisfying(entry -> {
            assertThat(entry.habousCityId()).isEqualTo("1");
            assertThat(entry.exactArabicName()).isEqualTo("الرباط");
            assertThat(entry.normalizedArabicName()).isEqualTo("الرباط");
            assertThat(entry.sourceChecksum()).hasSize(64);
            assertThat(entry.discoveredAt()).isEqualTo(Instant.parse("2026-07-15T03:00:00Z"));
            assertThat(entry.lastSeenAt()).isEqualTo(Instant.parse("2026-07-15T03:00:00Z"));
        });
    }

    @Test
    void theObservedCountReplacesTheHardCoded191WhenTheSourceChanges() {
        // The official source now publishes only two cities. The API must report
        // what was observed, not the bundled 191.
        service.refresh(pageWith(option("1", "الرباط"), option("117", "أكادير")));

        assertThat(service.observedCount()).isEqualTo(2);
        assertThat(service.collectableVilleIds()).containsExactly("1", "117");
    }

    @Test
    void aNewOfficialCityIsDetected() {
        service.refresh(pageWith(option("1", "الرباط")));

        var result = service.refresh(pageWith(option("1", "الرباط"), option("9001", "مدينة جديدة")));

        assertThat(result.diff().newCities()).containsExactly("9001");
        assertThat(result.diff().hasChanges()).isTrue();
    }

    @Test
    void aRemovedCityIsDetectedButItsHistoryIsRetained() {
        service.refresh(pageWith(option("1", "الرباط"), option("117", "أكادير")));

        var result = service.refresh(pageWith(option("1", "الرباط")));

        assertThat(result.diff().removedCities()).containsExactly("117");
        assertThat(result.catalogue().observedCount()).isEqualTo(1);

        // Never silently deleted: the entry survives, marked absent, so raw
        // snapshots already collected under ville 117 stay explicable.
        assertThat(result.catalogue().byId("117")).hasValueSatisfying(entry -> {
            assertThat(entry.present()).isFalse();
            assertThat(entry.exactArabicName()).isEqualTo("أكادير");
            assertThat(entry.lastSeenAt()).isNotNull();
        });
        assertThat(result.catalogue().absentEntries())
                .extracting(CityCatalogueEntry::habousCityId)
                .contains("117");

        // Retention is total, not just for the city that vanished this round: the
        // bundled bootstrap cities this fixture never published are all still
        // carried, marked absent, rather than dropped from the catalogue.
        assertThat(result.catalogue().entries()).hasSize(191);
    }

    @Test
    void aRenamedCityIsDetectedAndTheOldLabelIsKept() {
        service.refresh(pageWith(option("1", "الرباط")));

        var result = service.refresh(pageWith(option("1", "الرِباط")));

        assertThat(result.diff().renamedCities()).singleElement().satisfies(rename -> {
            assertThat(rename.habousCityId()).isEqualTo("1");
            assertThat(rename.previousName()).isEqualTo("الرباط");
            assertThat(rename.currentName()).isEqualTo("الرِباط");
        });
        assertThat(result.catalogue().byId("1").orElseThrow().previousNames())
                .containsExactly("الرباط");
    }

    @Test
    void aDuplicateIdIsDetected() {
        var result = service.refresh(pageWith(option("1", "الرباط"), option("1", "مدينة أخرى")));

        assertThat(result.diff().duplicateIds()).containsExactly("1");
        assertThat(result.diff().hasAnomalies()).isTrue();
    }

    @Test
    void aDuplicateExactLabelIsDetected() {
        var result = service.refresh(pageWith(option("1", "الرباط"), option("2", "الرباط")));

        assertThat(result.diff().duplicateLabels()).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.exactArabicName()).isEqualTo("الرباط");
            assertThat(duplicate.habousCityIds()).containsExactly("1", "2");
        });
        assertThat(result.diff().hasAnomalies()).isTrue();
    }

    @Test
    void anUnchangedSourceReportsNoChanges() {
        String html = pageWith(option("1", "الرباط"), option("117", "أكادير"));
        service.refresh(html);

        var result = service.refresh(html);

        assertThat(result.diff().hasChanges()).isFalse();
        assertThat(result.diff().hasAnomalies()).isFalse();
    }

    @Test
    void theRefreshedCatalogueIsStoredUnderRuntimeData() throws IOException {
        service.refresh(pageWith(option("1", "الرباط")));

        Path stored = properties.currentCatalogFile();
        assertThat(stored).isRegularFile();
        assertThat(Files.readString(stored)).contains("الرباط").contains("\"present\" : true");
    }

    @Test
    void aStoredCatalogueIsReloadedRatherThanRebootstrapped() {
        service.refresh(pageWith(option("1", "الرباط")));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HabousCityCatalogService restarted = new HabousCityCatalogService(
                new OfficialHabousCityDirectory(),
                new HabousVilleDirectoryParser(new Sha256Service()),
                new AtomicJsonFileWriter(mapper),
                properties,
                clock);

        // A restart must not silently revert to the bundled 191 and forget that
        // the official source now publishes one city.
        assertThat(restarted.observedCount()).isEqualTo(1);
    }

    @Test
    void aPageWithoutTheVilleSelectIsRefusedAndTheKnownCatalogueSurvives() {
        service.refresh(pageWith(option("1", "الرباط"), option("117", "أكادير")));

        // A maintenance page must not wipe the catalogue to zero cities.
        assertThatThrownBy(() -> service.refresh("<html><body>الموقع في صيانة</body></html>"))
                .isInstanceOf(HabousParseException.class);

        assertThat(service.observedCount()).isEqualTo(2);
    }

    @Test
    void theBootstrapIndexItselfIsNotTreatedAsAnObservation() {
        // Bootstrap entries carry a sentinel checksum, so they are distinguishable
        // from a genuine observation of the official page.
        CityCatalogue catalogue = service.current();
        assertThat(catalogue.sourceChecksum()).isEqualTo("bootstrap");
        assertThat(catalogue.byId("1").orElseThrow().sourceChecksum()).isEqualTo("bootstrap");
    }

    @Test
    void unchangedDiffHelperReportsNothing() {
        CatalogueDiff diff = CatalogueDiff.unchanged();
        assertThat(diff.hasChanges()).isFalse();
        assertThat(diff.hasAnomalies()).isFalse();
        assertThat(List.of(diff.newCities(), diff.removedCities(), diff.renamedCities()))
                .allSatisfy(list -> assertThat(list).isEmpty());
    }
}
