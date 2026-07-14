package io.github.cyonecoder.prayertime.infrastructure.habous;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousResearchTls;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in tests that hit the real Habous endpoint over the network.
 *
 * <p>Tagged {@code "live"} and therefore excluded from the normal {@code test}
 * task; run them explicitly with {@code ./gradlew liveTest}. They require
 * internet access.
 */
@Tag("live")
class HabousLiveTest {

    @Test
    void fetchesRealMonthlyHtmlFromHabous() {
        // Fetch the way the application actually reaches Habous: the trust-augmented
        // client that supplies the bundled Sectigo intermediate the server omits.
        // (The default JDK truststore cannot build the cert path on its own.)
        HabousClient client = HabousClient.withHttpClient(HabousResearchTls.trustingHttpClient());

        String html = client.fetchMonthlyHtml("1", LocalDate.now()); // ville=1 (Rabat)

        assertThat(html).isNotBlank();
        // The Habous prayer page always carries the ville dropdown the parsers rely on
        // (the page markup omits a top-level <html> tag).
        assertThat(html).contains("name=\"ville\"");
    }
}
