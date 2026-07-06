package io.github.cyonecoder.prayertime.infrastructure.habous;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in tests that hit the real Habous endpoint over the network.
 *
 * <p>Tagged {@code "live"} and therefore excluded from the normal {@code test}
 * task; run them explicitly with {@code ./gradlew liveTest}. They require
 * internet access and are allowed to be flaky/unavailable.
 */
@Tag("live")
class HabousLiveTest {

    @Test
    void fetchesRealMonthlyHtmlFromHabous() {
        HabousClient client = new HabousClient();

        // "casablanca" is a placeholder Habous "ville" value; the assertion only
        // checks that a real HTML document comes back over TLS.
        String html = client.fetchMonthlyHtml("casablanca", LocalDate.now());

        assertThat(html).isNotBlank();
        assertThat(html.toLowerCase()).contains("<html");
    }
}
