package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HabousVilleDirectoryTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/habous/horaire-rabat-live-2026-muharram.html");

    private String fixture() throws IOException {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    @Test
    void parsesEveryVilleOptionWithProvenance() throws IOException {
        HabousVilleDirectory dir = HabousVilleDirectory.parse(fixture());
        assertThat(dir.options()).hasSizeGreaterThan(180);
        assertThat(dir.sourceChecksum()).hasSize(64);
        assertThat(dir.byId("1")).isPresent();
        assertThat(dir.byId("1").orElseThrow().exactArabicName()).isEqualTo("الرباط");
        assertThat(dir.byId("58").orElseThrow().exactArabicName()).isEqualTo("الدار البيضاء");
    }

    @Test
    void matchesUniqueArabicNameAndResolvesById() throws IOException {
        HabousVilleDirectory dir = HabousVilleDirectory.parse(fixture());
        assertThat(dir.matchByArabic("الرباط")).singleElement()
                .satisfies(o -> assertThat(o.habousCityId()).isEqualTo("1"));
        assertThat(dir.matchByArabic("لا-توجد-مدينة")).isEmpty();
    }

    @Test
    void reportsAmbiguousNamesAsMultipleHits() {
        // A synthetic select with two options sharing the same name.
        String html = "<select name=\"ville\">"
                + "<option value='index.php?ville=900'>مدينة</option>"
                + "<option value='index.php?ville=901'>مدينة</option>"
                + "</select>";
        HabousVilleDirectory dir = HabousVilleDirectory.parse(html);
        assertThat(dir.matchByArabic("مدينة")).hasSize(2);
    }

    @Test
    void rejectsPageWithoutVilleSelect() {
        assertThatThrownBy(() -> HabousVilleDirectory.parse("<html><body>nothing</body></html>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("select");
    }
}
