package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousSnapshotStore.SaveStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HabousSnapshotStoreTest {

    /** Builds a month whose recorded checksum matches the given source html (the store contract). */
    private static HabousReferenceMonth monthFor(String html) {
        return new HabousReferenceMonth("casablanca", "الدار البيضاء", "الدار البيضاء",
                2026, 7, Instant.parse("2026-07-01T00:00:00Z"),
                "https://example/ville", HabousSnapshotStore.sha256Hex(html), "habous-html-1",
                List.of(new HabousReferenceDay(LocalDate.of(2026, 7, 1),
                        LocalTime.of(5, 1), LocalTime.of(6, 1), LocalTime.of(13, 1),
                        LocalTime.of(17, 1), LocalTime.of(20, 1), LocalTime.of(22, 1))));
    }

    @Test
    void sha256IsStableAndHex() {
        String a = HabousSnapshotStore.sha256Hex("hello");
        assertThat(a).isEqualTo(HabousSnapshotStore.sha256Hex("hello"))
                .hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a).isNotEqualTo(HabousSnapshotStore.sha256Hex("world"));
    }

    @Test
    void createsThenReadsBackAllFilesAndPreservesArabicEncoding(@TempDir Path root) {
        HabousSnapshotStore store = new HabousSnapshotStore(root);
        String html = "<html>الدار البيضاء 2026</html>";
        HabousSnapshotStore.SaveResult result = store.save(monthFor(html), html);

        assertThat(result.status()).isEqualTo(SaveStatus.CREATED);
        Path dir = store.primaryDirectory("casablanca", 2026, 7);
        assertThat(dir.resolve("source.html")).exists();
        assertThat(dir.resolve("source.sha256")).exists();
        assertThat(dir.resolve("prayer-times.csv")).exists();
        assertThat(dir.resolve("metadata.json")).exists();

        HabousSnapshotStore.StoredSnapshot read = store.readPrimary("casablanca", 2026, 7).orElseThrow();
        assertThat(read.month().habousArabicName()).isEqualTo("الدار البيضاء");
        assertThat(read.month().dailyRecords()).hasSize(1);
        assertThat(read.checksum()).isEqualTo(result.checksum());
    }

    @Test
    void identicalSourceIsReportedAsDuplicateAndNotRewritten(@TempDir Path root) throws Exception {
        HabousSnapshotStore store = new HabousSnapshotStore(root);
        String html = "<html>same 2026</html>";
        store.save(monthFor(html), html);
        Path htmlFile = store.primaryDirectory("casablanca", 2026, 7).resolve("source.html");
        Instant before = Files.getLastModifiedTime(htmlFile).toInstant();

        HabousSnapshotStore.SaveResult second = store.save(monthFor(html), html);
        assertThat(second.status()).isEqualTo(SaveStatus.DUPLICATE);
        assertThat(Files.getLastModifiedTime(htmlFile).toInstant()).isEqualTo(before);
    }

    @Test
    void changedSourceIsSavedAsARevisionAndPrimaryIsUntouched(@TempDir Path root) throws Exception {
        HabousSnapshotStore store = new HabousSnapshotStore(root);
        store.save(monthFor("<html>original 2026</html>"), "<html>original 2026</html>");
        Path primaryHtml = store.primaryDirectory("casablanca", 2026, 7).resolve("source.html");
        String primaryContent = Files.readString(primaryHtml, StandardCharsets.UTF_8);

        HabousSnapshotStore.SaveResult revision =
                store.save(monthFor("<html>CHANGED 2026</html>"), "<html>CHANGED 2026</html>");
        assertThat(revision.status()).isEqualTo(SaveStatus.REVISION_SAVED);
        assertThat(revision.location()).isNotEqualTo(store.primaryDirectory("casablanca", 2026, 7));
        assertThat(HabousSnapshotStore.isRevision(revision.location())).isTrue();
        // The primary source is never overwritten.
        assertThat(Files.readString(primaryHtml, StandardCharsets.UTF_8)).isEqualTo(primaryContent);

        // Both primary and revision are discoverable by a scan.
        assertThat(store.scanAll()).hasSize(2);
    }
}
