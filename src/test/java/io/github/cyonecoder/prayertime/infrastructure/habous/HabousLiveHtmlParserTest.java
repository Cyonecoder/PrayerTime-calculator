package io.github.cyonecoder.prayertime.infrastructure.habous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser.HabousLiveDay;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser.HabousParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parses the real, captured live Habous page and checks the Gregorian rollover. */
class HabousLiveHtmlParserTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/fixtures/habous/horaire-rabat-live-2026-muharram.html");

    private String fixture() throws IOException {
        return Files.readString(FIXTURE, StandardCharsets.UTF_8);
    }

    @Test
    void parsesRealPageAndResolvesGregorianDatesAcrossTheMonthWrap() throws IOException {
        List<HabousLiveDay> days = new HabousLiveHtmlParser().parse(fixture());

        // The captured Muharram month runs 2026-06-17 .. 2026-07-16 (30 days).
        assertThat(days).hasSize(30);
        assertThat(days.get(0).date()).isEqualTo(LocalDate.of(2026, 6, 17));
        assertThat(days).extracting(HabousLiveDay::date).doesNotHaveDuplicates();
        // Dates are strictly consecutive across the June→July boundary.
        for (int i = 1; i < days.size(); i++) {
            assertThat(days.get(i).date())
                    .as("row %d consecutive", i)
                    .isEqualTo(days.get(i - 1).date().plusDays(1));
        }
        assertThat(days.get(days.size() - 1).date()).isEqualTo(LocalDate.of(2026, 7, 16));

        // First row's times match the captured page exactly.
        HabousLiveDay first = days.get(0);
        assertThat(first.fajr()).isEqualTo(LocalTime.of(4, 24));
        assertThat(first.sunrise()).isEqualTo(LocalTime.of(6, 13));
        assertThat(first.dhuhr()).isEqualTo(LocalTime.of(13, 33));
        assertThat(first.asr()).isEqualTo(LocalTime.of(17, 14));
        assertThat(first.maghrib()).isEqualTo(LocalTime.of(20, 45));
        assertThat(first.isha()).isEqualTo(LocalTime.of(22, 19));

        // Prayer order holds for every parsed day (sanity against column drift).
        for (HabousLiveDay d : days) {
            assertThat(d.fajr()).isBefore(d.sunrise());
            assertThat(d.sunrise()).isBefore(d.dhuhr());
            assertThat(d.dhuhr()).isBefore(d.asr());
            assertThat(d.asr()).isBefore(d.maghrib());
            assertThat(d.maghrib()).isBefore(d.isha());
        }
    }

    @Test
    void rejectsHtmlWithoutTheHoraireTable() {
        assertThatThrownBy(() -> new HabousLiveHtmlParser().parse("<html><body>no table</body></html>"))
                .isInstanceOf(HabousParseException.class);
    }
}
