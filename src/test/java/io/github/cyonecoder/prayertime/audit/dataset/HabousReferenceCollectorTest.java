package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Outcome;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Record;
import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Status;
import io.github.cyonecoder.prayertime.audit.dataset.HabousTestFixtures.FlakyFetcher;
import io.github.cyonecoder.prayertime.audit.dataset.HabousTestFixtures.RecordingFetcher;
import io.github.cyonecoder.prayertime.audit.dataset.HabousTestFixtures.RecordingSleeper;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HabousReferenceCollectorTest {

    private static final String CASA = "الدار البيضاء";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private HabousReferenceCollector collector(
            Fetcher fetcher, HabousSnapshotStore store, CollectionSettings settings, RecordingSleeper sleeper) {
        return new HabousReferenceCollector(fetcher, new HabousHtmlParser(), store,
                HabousCityMapping.load(), FIXED, sleeper, settings);
    }

    private CollectionSettings settings(int maxRetries, boolean refresh) {
        return CollectionSettings.defaults()
                .withRequestDelay(Duration.ofSeconds(1))
                .withMaxRetries(maxRetries)
                .withRefresh(refresh);
    }

    private Record only(Outcome outcome) {
        assertThat(outcome.records()).hasSize(1);
        return outcome.records().get(0);
    }

    @Test
    void validMonthIsCreatedWithFullDaysAndDelayApplied(@TempDir Path root) {
        RecordingFetcher fetcher = new RecordingFetcher((id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7));
        RecordingSleeper sleeper = new RecordingSleeper();
        HabousSnapshotStore store = new HabousSnapshotStore(root);

        Record record = only(collector(fetcher, store, settings(3, false), sleeper)
                .collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(record.status()).isEqualTo(Status.CREATED);
        assertThat(record.dayCount()).isEqualTo(31);
        assertThat(sleeper.sleeps.get()).isEqualTo(1);
        assertThat(store.primaryExists("casablanca", 2026, 7)).isTrue();
    }

    @Test
    void incompleteTableIsRejected(@TempDir Path root) {
        Fetcher fetcher = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7, 30);
        HabousSnapshotStore store = new HabousSnapshotStore(root);

        Record record = only(collector(fetcher, store, settings(3, false), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(record.status()).isEqualTo(Status.INCOMPLETE_TABLE);
        assertThat(store.primaryExists("casablanca", 2026, 7)).isFalse();
    }

    @Test
    void leapYearFebruaryCollectsTwentyNineDays(@TempDir Path root) {
        Fetcher fetcher = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2024, 2);
        HabousSnapshotStore store = new HabousSnapshotStore(root);

        Record record = only(collector(fetcher, store, settings(3, false), new RecordingSleeper())
                .collect(List.of("casablanca"), 2024, List.of(2)));

        assertThat(record.status()).isEqualTo(Status.CREATED);
        assertThat(record.dayCount()).isEqualTo(29);
    }

    @Test
    void malformedHtmlIsRejected(@TempDir Path root) {
        Fetcher fetcher = (id, d) -> HabousTestFixtures.malformedTableHtml(CASA, 2026, 7);
        Record record = only(collector(fetcher, new HabousSnapshotStore(root), settings(3, false),
                new RecordingSleeper()).collect(List.of("casablanca"), 2026, List.of(7)));
        assertThat(record.status()).isEqualTo(Status.INVALID_PAGE);
    }

    @Test
    void wrongCityPageIsRejected(@TempDir Path root) {
        // Returns Tangier's page for a Casablanca request.
        Fetcher fetcher = (id, d) -> HabousTestFixtures.monthHtml("طنجة", 2026, 7);
        Record record = only(collector(fetcher, new HabousSnapshotStore(root), settings(3, false),
                new RecordingSleeper()).collect(List.of("casablanca"), 2026, List.of(7)));
        assertThat(record.status()).isEqualTo(Status.WRONG_CITY);
    }

    @Test
    void wrongPeriodPageIsRejected(@TempDir Path root) {
        // Returns a 2025 page for a 2026 request.
        Fetcher fetcher = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2025, 7);
        Record record = only(collector(fetcher, new HabousSnapshotStore(root), settings(3, false),
                new RecordingSleeper()).collect(List.of("casablanca"), 2026, List.of(7)));
        assertThat(record.status()).isEqualTo(Status.WRONG_PERIOD);
    }

    @Test
    void secondRunReusesCacheWithNoDuplicateRequest(@TempDir Path root) {
        RecordingFetcher fetcher = new RecordingFetcher((id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7));
        HabousSnapshotStore store = new HabousSnapshotStore(root);

        collector(fetcher, store, settings(3, false), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7));
        Record second = only(collector(fetcher, store, settings(3, false), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(second.status()).isEqualTo(Status.CACHED);
        assertThat(second.attempts()).isZero();
        assertThat(fetcher.calls.get()).isEqualTo(1); // no second network request
    }

    @Test
    void refreshWithIdenticalSourceIsADuplicate(@TempDir Path root) {
        Fetcher fetcher = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7);
        HabousSnapshotStore store = new HabousSnapshotStore(root);

        collector(fetcher, store, settings(3, false), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7));
        Record refreshed = only(collector(fetcher, store, settings(3, true), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(refreshed.status()).isEqualTo(Status.DUPLICATE);
    }

    @Test
    void changedChecksumIsSavedAsARevision(@TempDir Path root) {
        HabousSnapshotStore store = new HabousSnapshotStore(root);
        Fetcher original = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7);
        collector(original, store, settings(3, false), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7));

        Fetcher changed = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7) + "<!-- edited -->";
        Record revision = only(collector(changed, store, settings(3, true), new RecordingSleeper())
                .collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(revision.status()).isEqualTo(Status.REVISION_SAVED);
        assertThat(store.scanAll()).hasSize(2);
    }

    @Test
    void httpTimeoutExhaustsBoundedRetriesThenFails(@TempDir Path root) {
        FlakyFetcher fetcher = new FlakyFetcher(99, HabousTestFixtures.monthHtml(CASA, 2026, 7));
        RecordingSleeper sleeper = new RecordingSleeper();

        Record record = only(collector(fetcher, new HabousSnapshotStore(root), settings(2, false), sleeper)
                .collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(record.status()).isEqualTo(Status.FETCH_FAILED);
        assertThat(fetcher.calls.get()).isEqualTo(3); // maxRetries(2) + 1
        assertThat(sleeper.sleeps.get()).isEqualTo(3);
    }

    @Test
    void http502RecoversWithinRetryBudget(@TempDir Path root) {
        FlakyFetcher fetcher = new FlakyFetcher(2, HabousTestFixtures.monthHtml(CASA, 2026, 7));

        Record record = only(collector(fetcher, new HabousSnapshotStore(root), settings(3, false),
                new RecordingSleeper()).collect(List.of("casablanca"), 2026, List.of(7)));

        assertThat(record.status()).isEqualTo(Status.CREATED);
        assertThat(record.attempts()).isEqualTo(3);
        assertThat(fetcher.calls.get()).isEqualTo(3);
    }

    @Test
    void unmappedCityIsReported(@TempDir Path root) {
        Fetcher fetcher = (id, d) -> HabousTestFixtures.monthHtml(CASA, 2026, 7);
        Record record = only(collector(fetcher, new HabousSnapshotStore(root), settings(3, false),
                new RecordingSleeper()).collect(List.of("ifrane"), 2026, List.of(7)));
        assertThat(record.status()).isEqualTo(Status.UNMAPPED);
    }
}
