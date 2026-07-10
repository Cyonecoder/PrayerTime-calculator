package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Outcome;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in test that hits the real Habous endpoint. Tagged {@code "live"} and
 * excluded from the normal {@code test} task; run with {@code ./gradlew liveTest}.
 *
 * <p>It is lenient by design: the Habous mapping ids are not yet verified, so a
 * fetch may legitimately fail. The test only asserts the collector completes and
 * produces a record for the requested city-month.
 */
@Tag("live")
class HabousDatasetLiveTest {

    @Test
    void collectsOrCleanlyReportsForAMappedCity(@TempDir Path root) {
        HabousReferenceCollector collector = new HabousReferenceCollector(
                new HabousClient()::fetchMonthlyHtml,
                new HabousHtmlParser(),
                new HabousSnapshotStore(root),
                HabousCityMapping.load(),
                Clock.systemUTC(),
                duration -> Thread.sleep(duration.toMillis()),
                CollectionSettings.defaults().withRequestDelay(Duration.ofSeconds(1)));

        Outcome outcome = collector.collect(List.of("casablanca"), 2026, List.of(7));

        assertThat(outcome.records()).hasSize(1);
        assertThat(outcome.records().get(0).applicationCityId()).isEqualTo("casablanca");
    }
}
