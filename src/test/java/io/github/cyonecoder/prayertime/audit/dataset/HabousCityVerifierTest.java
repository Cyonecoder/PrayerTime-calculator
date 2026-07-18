package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Candidate;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Result;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Status;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HabousCityVerifierTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/habous/horaire-rabat-live-2026-muharram.html");

    private final HabousVilleDirectory directory = HabousVilleDirectory.parse(fixture());
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC);

    private static String fixture() {
        try {
            return Files.readString(FIXTURE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private HabousCityVerifier verifier(Fetcher fetcher) {
        return new HabousCityVerifier(fetcher, new HabousLiveHtmlParser(), clock,
                Duration.ZERO, d -> { });
    }

    private Result verify(Fetcher fetcher, Candidate c) {
        return verifier(fetcher).verify(c, directory, Map.of(), Map.of());
    }

    private static Candidate candidate(String slug, String arabic, String... aliases) {
        return new Candidate(slug, slug, arabic, List.of(aliases));
    }

    @Test
    void verifiesWhenNameTimetableAndSecondFetchAllAgree() {
        Result r = verify((id, d) -> fixture(), candidate("rabat", "الرباط"));
        assertThat(r.status()).isEqualTo(Status.VERIFIED);
        assertThat(r.habousCityId()).isEqualTo("1");
        assertThat(r.matchMethod()).isEqualTo("arabic-name");
        assertThat(r.confirmed()).isTrue();
        assertThat(r.sourceChecksum()).hasSize(64);
    }

    @Test
    void verifiesViaExactArabicAliasWhenInventoryNameDiffers() {
        // Inventory name جرسيف does not exist in the dropdown, but the known Habous
        // alias كرسيف does — an exact (non-fuzzy) alias match, still fully confirmed.
        Result r = verify((id, d) -> fixture(), candidate("guercif", "جرسيف", "كرسيف"));
        assertThat(r.status()).isEqualTo(Status.VERIFIED);
        assertThat(r.matchMethod()).isEqualTo("arabic-alias");
        assertThat(r.habousCityId()).isEqualTo("35");
    }

    @Test
    void unlistedNameIsNotListed() {
        Result r = verify((id, d) -> fixture(), candidate("atlantis", "أطلانطسيا"));
        assertThat(r.status()).isEqualTo(Status.NOT_LISTED_BY_HABOUS);
        assertThat(r.habousCityId()).isNull();
    }

    @Test
    void fetchFailureIsTimetableUnavailable() {
        Fetcher failing = (id, d) -> {
            throw new HabousUnavailableException("boom");
        };
        Result r = verify(failing, candidate("rabat", "الرباط"));
        assertThat(r.status()).isEqualTo(Status.TIMETABLE_UNAVAILABLE);
    }

    @Test
    void pageMissingExpectedNameIsInvalidTimetable() {
        Result r = verify((id, d) -> "<html><body>wrong page</body></html>",
                candidate("rabat", "الرباط"));
        assertThat(r.status()).isEqualTo(Status.INVALID_TIMETABLE);
    }

    @Test
    void secondFetchThatDiffersDropsToManualReview() {
        // First fetch is the real table; the confirming fetch returns a table with a
        // different Fajr time, so the two do not agree and the mapping is not approved.
        String altered = fixture().replaceFirst(">04:", ">05:");
        AtomicInteger calls = new AtomicInteger();
        Fetcher flaky = (id, d) -> calls.getAndIncrement() == 0 ? fixture() : altered;
        Result r = verify(flaky, candidate("rabat", "الرباط"));
        assertThat(r.status()).isEqualTo(Status.MANUAL_REVIEW_REQUIRED);
        assertThat(r.confirmed()).isFalse();
    }

    @Test
    void ambiguousNameIsNeverApproved() {
        String html = "<select name=\"ville\">"
                + "<option value='index.php?ville=900'>مدينة</option>"
                + "<option value='index.php?ville=901'>مدينة</option>"
                + "</select>";
        HabousVilleDirectory ambiguous = HabousVilleDirectory.parse(html);
        Result r = verifier((id, d) -> fixture())
                .verify(candidate("x", "مدينة"), ambiguous, Map.of(), Map.of());
        assertThat(r.status()).isEqualTo(Status.AMBIGUOUS_NAME);
    }
}
