package io.github.cyonecoder.salatapi.habous.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A lightweight live check against the real Habous endpoint (spec 29).
 *
 * <p><strong>Opt-in only.</strong> Tagged {@code live}, so the normal {@code test}
 * task excludes it; run it explicitly with {@code ./gradlew liveTest}. It makes
 * exactly <strong>one</strong> request (Rabat, ville 1) and, from that single
 * page, validates both the official catalogue (the {@code <select name="ville">})
 * and one city's timetable. It never crawls all 191 cities, and it does not assert
 * exact times or a specific Hijri month.
 */
@Tag("live")
@SpringBootTest(properties = "salat.scheduling.enabled=false")
class HabousClientLiveTest {

    @Autowired
    private HabousClient client;
    @Autowired
    private HabousLiveHtmlParser timetableParser;
    @Autowired
    private HabousVilleDirectoryParser villeParser;

    @Test
    void oneFetchValidatesTheCatalogueAndOneCityOverValidatedTls() {
        // Exactly one request — the ville=1 page carries both the ville dropdown
        // (the catalogue) and Rabat's timetable.
        String html = client.fetchCurrentPublishedTimetable("1");
        assertThat(html).as("TLS succeeded and content returned").isNotBlank();

        // (a) The official catalogue: the <select name="ville"> lists many cities.
        HabousVilleDirectoryParser.Directory catalogue = villeParser.parse(html);
        assertThat(catalogue.count())
                .as("official ville catalogue is substantial (not crawled per-city)")
                .isGreaterThan(150);
        assertThat(catalogue.sourceChecksum()).hasSize(64);

        // (b) One city's timetable is structurally a valid, contiguous month.
        var days = timetableParser.parse(html);
        assertThat(days).isNotEmpty();
        for (int i = 1; i < days.size(); i++) {
            assertThat(days.get(i).date()).isEqualTo(days.get(i - 1).date().plusDays(1));
        }

        // (c) When the selected-city marker is present, it is Rabat (ville 1).
        villeParser.selectedVille(html).ifPresent(selected ->
                assertThat(selected.habousCityId()).isEqualTo("1"));
    }

    @Test
    void trustIsAugmentedNotDisabled() {
        // The composite trust manager advertises the platform CAs plus the one
        // bundled intermediate — additive, never a trust-all context.
        List<?> issuers = List.of(HabousTrustStore.compositeTrustManager().getAcceptedIssuers());
        assertThat(issuers).isNotEmpty();
    }
}
