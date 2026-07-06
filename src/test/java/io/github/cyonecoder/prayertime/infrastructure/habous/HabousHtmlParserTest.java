package io.github.cyonecoder.prayertime.infrastructure.habous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousDay;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class HabousHtmlParserTest {

    private final HabousHtmlParser parser = new HabousHtmlParser();

    private static String fixture(String name) throws IOException {
        try (InputStream in =
                     HabousHtmlParserTest.class.getResourceAsStream("/fixtures/habous/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesEveryDayRowFromTheFixture() throws IOException {
        List<HabousDay> days = parser.parse(fixture("horaire-casablanca-2026-07.html"));

        assertThat(days).hasSize(30);
        assertThat(days.get(0).gregorianDay()).isEqualTo(1);
        assertThat(days.get(29).gregorianDay()).isEqualTo(30);

        HabousDay day15 = days.stream().filter(d -> d.gregorianDay() == 15).findFirst().orElseThrow();
        assertThat(day15.fajr()).isEqualTo(LocalTime.of(5, 15));
        assertThat(day15.sunrise()).isEqualTo(LocalTime.of(6, 15));
        assertThat(day15.dhuhr()).isEqualTo(LocalTime.of(12, 15));
        assertThat(day15.asr()).isEqualTo(LocalTime.of(16, 15));
        assertThat(day15.maghrib()).isEqualTo(LocalTime.of(19, 15));
        assertThat(day15.isha()).isEqualTo(LocalTime.of(20, 15));
    }

    @Test
    void throwsWhenTableIsMissing() {
        assertThatExceptionOfType(HabousParseException.class)
                .isThrownBy(() -> parser.parse("<html><body><p>no table here</p></body></html>"))
                .withMessageContaining("horaire");
    }

    @Test
    void rejectsMalformedRowWithTooFewColumns() {
        String html = """
                <table id="horaire">
                  <tr><th>day</th><th>fajr</th></tr>
                  <tr><td>1</td><td>05:10</td><td>06:40</td></tr>
                </table>
                """;
        assertThatExceptionOfType(HabousParseException.class)
                .isThrownBy(() -> parser.parse(html))
                .withMessageContaining("Malformed row");
    }

    @Test
    void rejectsUnparseableTime() {
        String html = """
                <table id="horaire">
                  <tr><td>1</td><td>9:99</td><td>06:40</td><td>12:30</td>
                      <td>16:05</td><td>19:15</td><td>20:40</td></tr>
                </table>
                """;
        assertThatExceptionOfType(HabousParseException.class)
                .isThrownBy(() -> parser.parse(html))
                .withMessageContaining("Unparseable time");
    }
}
