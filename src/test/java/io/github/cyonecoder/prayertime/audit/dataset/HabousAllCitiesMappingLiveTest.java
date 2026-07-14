package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Candidate;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Result;
import io.github.cyonecoder.prayertime.audit.dataset.HabousCityVerifier.Status;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in live recheck of the committed mappings against the real Habous source.
 * Tagged {@code "live"} and excluded from the normal {@code test} task (run with
 * {@code ./gradlew liveTest}). Rechecks a controlled sample by default; set
 * {@code -DhabousLiveAll=true} to recheck every mapping.
 */
@Tag("live")
class HabousAllCitiesMappingLiveTest {

    private static final List<String> SAMPLE = List.of("casablanca", "rabat", "safi", "nador");

    @Test
    void mappedSampleStillResolvesToItsCommittedVilleIdLive() {
        HabousCityMapping mapping = HabousCityMapping.load();
        Fetcher fetcher = HabousClient.withHttpClient(HabousResearchTls.trustingHttpClient())
                ::fetchMonthlyHtml;
        HabousVilleDirectory directory = HabousVilleDirectory.parse(
                fetcher.fetchMonthlyHtml("1", LocalDate.now()));
        HabousCityVerifier verifier = new HabousCityVerifier(
                fetcher, new HabousLiveHtmlParser(), Clock.systemUTC(),
                Duration.ofSeconds(1), d -> Thread.sleep(d.toMillis()));

        Map<String, String> verifiedIds = mapping.all().stream()
                .collect(Collectors.toMap(
                        HabousCityMapping.Entry::applicationCityId,
                        HabousCityMapping.Entry::habousCityId));

        boolean all = Boolean.getBoolean("habousLiveAll");
        List<String> cities = all ? mapping.mappedCityIds() : SAMPLE;
        for (String city : cities) {
            HabousCityMapping.Entry entry = mapping.forCity(city).orElseThrow();
            Candidate candidate = new Candidate(
                    city, city, entry.habousArabicName(), List.of(entry.habousArabicName()));
            Result r = verifier.verify(candidate, directory, verifiedIds, Map.of());
            assertThat(r.status()).as("%s", city).isEqualTo(Status.VERIFIED);
            assertThat(r.habousCityId()).as("%s id", city).isEqualTo(entry.habousCityId());
        }
    }
}
