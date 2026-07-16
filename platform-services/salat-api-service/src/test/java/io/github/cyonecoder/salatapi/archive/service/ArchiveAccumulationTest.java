package io.github.cyonecoder.salatapi.archive.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.archive.repository.JsonMonthlyArchiveRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.city.ApplicationHabousMapping;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.city.MoroccoCityRegistry;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import io.github.cyonecoder.salatapi.secapproach.configuration.SecApproachParameters;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Gregorian month must <strong>accumulate</strong> across the two Hijri pages
 * that complete it, and reseeding must never drop days already collected.
 *
 * <p>Regression guard: the daily sync once rebuilt each month from only the current
 * run's pages, so a second sync with the next Hijri page silently dropped the days
 * the first page had contributed — a previously-served date would then return
 * {@code OFFICIAL_DATA_NOT_AVAILABLE}.
 */
class ArchiveAccumulationTest {

    @TempDir
    Path dir;

    private JsonMonthlyArchiveRepository repo;
    private MonthlyArchiveService service;

    private final List<CityCatalogueEntry> cities = List.of(new CityCatalogueEntry(
            "1", "الرباط", "الرباط", "b", Instant.EPOCH, Instant.EPOCH, true, List.of()));

    @BeforeEach
    void setUp() {
        var props = new SalatApiProperties("Africa/Casablanca", dir);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var jsonFiles = new AtomicJsonFileWriter(mapper);
        var sha = new Sha256Service();
        var support = new CitySupportService(new MoroccoCityRegistry(), new OfficialHabousCityDirectory(),
                new ApplicationHabousMapping(), new SecApproachParameters());
        repo = new JsonMonthlyArchiveRepository(props, jsonFiles, sha);
        service = new MonthlyArchiveService(new GregorianArchiveAssembler(support,
                Clock.fixed(Instant.parse("2026-07-20T03:00:00Z"), ZoneOffset.UTC)), repo);
    }

    /** A page (one raw snapshot) for ville 1 covering July {@code from..to}. */
    private ObservedPage july(int from, int to) {
        List<HabousPublishedDay> days = new ArrayList<>();
        for (int d = from; d <= to; d++) {
            days.add(new HabousPublishedDay(LocalDate.of(2026, 7, d),
                    LocalTime.of(4, 30), LocalTime.of(6, 15), LocalTime.of(13, 35),
                    LocalTime.of(17, 15), LocalTime.of(20, 45), LocalTime.of(22, 15)));
        }
        HabousPageSnapshot meta = new HabousPageSnapshot("1", "الرباط", "rabat", true,
                LocalDate.of(2026, 7, to), Instant.parse("2026-07-" + String.format("%02d", to) + "T03:00:00Z"),
                "u", "c" + from, "habous-live-1", days.size(), days.get(0).date(),
                days.get(days.size() - 1).date(), List.of("2026-07"));
        return new ObservedPage(meta, days);
    }

    private int julyDays() {
        return repo.findArchive(YearMonth.of(2026, 7)).orElseThrow().city("1").orElseThrow().daysAvailable();
    }

    @Test
    void reseedingWithTheNextHijriPageAccumulatesInsteadOfLosingEarlierDays() {
        ObservedPage firstHijri = july(1, 16);    // first page
        ObservedPage secondHijri = july(17, 31);  // next Hijri page, collected later

        // Run A stored only the first page.
        service.publishAccumulated(List.of(firstHijri), List.of(firstHijri),
                cities, Map.of(), Instant.now(), false, List.of());
        assertThat(julyDays()).isEqualTo(16);

        // Run B (reseed): the fresh page is the second Hijri page, but the store now
        // holds BOTH pages — so July must accumulate to the full month, not shrink.
        service.publishAccumulated(List.of(secondHijri), List.of(firstHijri, secondHijri),
                cities, Map.of(), Instant.now(), false, List.of());
        assertThat(julyDays())
                .as("July accumulates both Hijri pages rather than losing the first")
                .isEqualTo(31);
    }

    @Test
    void reRunningTheSameDayIsIdempotent() {
        ObservedPage page = july(16, 31);
        service.publishAccumulated(List.of(page), List.of(page), cities, Map.of(), Instant.now(), false, List.of());
        int first = julyDays();
        service.publishAccumulated(List.of(page), List.of(page), cities, Map.of(), Instant.now(), false, List.of());

        assertThat(julyDays()).isEqualTo(first).isEqualTo(16);
    }

    @Test
    void onlyTheMonthsTheFreshPagesTouchAreRepublished() {
        // Seed June and July from stored snapshots.
        ObservedPage junePage = new ObservedPage(
                new HabousPageSnapshot("1", "الرباط", "rabat", true, LocalDate.of(2026, 6, 30),
                        Instant.parse("2026-06-30T03:00:00Z"), "u", "cj", "habous-live-1", 1,
                        LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30), List.of("2026-06")),
                List.of(new HabousPublishedDay(LocalDate.of(2026, 6, 30),
                        LocalTime.of(4, 30), LocalTime.of(6, 15), LocalTime.of(13, 35),
                        LocalTime.of(17, 15), LocalTime.of(20, 45), LocalTime.of(22, 15))));
        ObservedPage julyPage = july(16, 31);

        // A fresh run whose pages touch only July must not rewrite June.
        service.publishAccumulated(List.of(julyPage), List.of(junePage, julyPage),
                cities, Map.of(), Instant.now(), false, List.of());

        assertThat(repo.findArchive(YearMonth.of(2026, 7))).isPresent();
        assertThat(repo.findArchive(YearMonth.of(2026, 6))).isEmpty(); // June not touched, not written
    }
}
