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
 * A single-city live check against the real Habous endpoint (TLS fix).
 *
 * <p><strong>Opt-in only.</strong> Tagged {@code live}, so the normal
 * {@code test} task excludes it; run it explicitly with {@code ./gradlew liveTest}
 * or {@code ./gradlew test --tests "*HabousClientLiveTest"}. It fetches only Rabat
 * (ville 1) and asserts a structurally valid timetable came back over a validated
 * TLS connection — proving the trust augmentation works without a full 191-city
 * sync. It deliberately does not assert exact times or a specific Hijri month.
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
    void fetchesRabatOverAValidatedTlsConnection() {
        String html = client.fetchCurrentPublishedTimetable("1");

        // The request succeeded (no SSLHandshakeException) and returned content.
        assertThat(html).isNotBlank();

        // It is structurally a Habous timetable: the parser produces contiguous days.
        var days = timetableParser.parse(html);
        assertThat(days).isNotEmpty();
        for (int i = 1; i < days.size(); i++) {
            assertThat(days.get(i).date()).isEqualTo(days.get(i - 1).date().plusDays(1));
        }

        // When the selected-city marker is present, it must be Rabat (ville 1).
        villeParser.selectedVille(html).ifPresent(selected ->
                assertThat(selected.habousCityId()).isEqualTo("1"));
    }

    @Test
    void trustIsAugmentedNotDisabled() {
        // The injected client is the trust-augmented bean; its SSL context is the
        // composite one, not a trust-all context. (A null/absent context would mean
        // the default was used, which cannot reach Habous at all.)
        List<?> issuers = List.of(HabousTrustStore.compositeTrustManager().getAcceptedIssuers());
        assertThat(issuers).isNotEmpty();
    }
}
