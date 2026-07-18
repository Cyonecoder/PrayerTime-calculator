package io.github.cyonecoder.salatapi.habous.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The Hijri→Gregorian resolution of the live official timetable.
 *
 * <p>Driven against a real captured Habous page (Rabat, Muharram 1448) rather
 * than a synthetic fixture, because the wrap behaviour is the whole point: the
 * page's Gregorian column runs 17…30 then 1…16 under the header "يونيو / يوليوز",
 * and getting that wrong misdates half the month by ~30 days.
 */
class HabousLiveHtmlParserTest {

    private static final String FIXTURE = "/fixtures/habous/horaire-rabat-live-2026-muharram.html";

    private final HabousLiveHtmlParser parser = new HabousLiveHtmlParser();

    private static String realPage() {
        try (InputStream in = HabousLiveHtmlParserTest.class.getResourceAsStream(FIXTURE)) {
            return new String(Objects.requireNonNull(in, "missing " + FIXTURE).readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theRealPageResolvesToThirtyConsecutiveGregorianDays() {
        List<HabousPublishedDay> days = parser.parse(realPage());

        assertThat(days).hasSize(30);
        assertThat(days.get(0).date()).isEqualTo(LocalDate.of(2026, 6, 17));
        assertThat(days.get(days.size() - 1).date()).isEqualTo(LocalDate.of(2026, 7, 16));

        // Strictly consecutive: a wrap handled wrongly shows up as a gap or a jump.
        for (int i = 1; i < days.size(); i++) {
            assertThat(days.get(i).date())
                    .as("day %d must follow day %d", i, i - 1)
                    .isEqualTo(days.get(i - 1).date().plusDays(1));
        }
    }

    @Test
    void oneHijriPageSpansTwoGregorianMonthsAndCompletesNeither() {
        Map<YearMonth, List<HabousPublishedDay>> byMonth = parser.parse(realPage()).stream()
                .collect(Collectors.groupingBy(d -> YearMonth.from(d.date())));

        // This is the archive's central problem: a single fetch yields a partial
        // June and a partial July. Neither Gregorian month can be published as
        // complete from this page alone.
        assertThat(byMonth).containsOnlyKeys(YearMonth.of(2026, 6), YearMonth.of(2026, 7));
        assertThat(byMonth.get(YearMonth.of(2026, 6))).hasSize(14);   // 17..30 June
        assertThat(byMonth.get(YearMonth.of(2026, 7))).hasSize(16);   // 1..16 July

        assertThat(byMonth.get(YearMonth.of(2026, 6))).allSatisfy(d ->
                assertThat(d.date().getDayOfMonth()).isBetween(17, 30));
        assertThat(byMonth.get(YearMonth.of(2026, 7))).allSatisfy(d ->
                assertThat(d.date().getDayOfMonth()).isBetween(1, 16));
    }

    @Test
    void theFirstAndLastRowsKeepTheirPublishedTimesExactly() {
        List<HabousPublishedDay> days = parser.parse(realPage());

        // Straight from the page's first data row: 04:24 06:13 13:33 17:14 20:45 22:19
        HabousPublishedDay first = days.get(0);
        assertThat(first.fajr()).isEqualTo(LocalTime.of(4, 24));
        assertThat(first.sunrise()).isEqualTo(LocalTime.of(6, 13));
        assertThat(first.dhuhr()).isEqualTo(LocalTime.of(13, 33));
        assertThat(first.asr()).isEqualTo(LocalTime.of(17, 14));
        assertThat(first.maghrib()).isEqualTo(LocalTime.of(20, 45));
        assertThat(first.isha()).isEqualTo(LocalTime.of(22, 19));

        // The final row's Hijri day reads "حسب نتيجة المراقبة" (subject to moon
        // sighting) rather than a number. The Gregorian column still says 16, and
        // the parser keys off that column, so the row must still be read.
        HabousPublishedDay last = days.get(days.size() - 1);
        assertThat(last.date()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(last.fajr()).isEqualTo(LocalTime.of(4, 40));
        assertThat(last.isha()).isEqualTo(LocalTime.of(22, 13));
    }

    @Test
    void thePageCoversExactlyTheSecApproachTrainingWindow() {
        List<HabousPublishedDay> days = parser.parse(realPage());

        // Not a coincidence worth losing: the released v2 offsets were trained on
        // 2026-06-17..2026-07-16, which is exactly this Hijri month.
        assertThat(days.get(0).date()).isEqualTo(LocalDate.of(2026, 6, 17));
        assertThat(days.get(29).date()).isEqualTo(LocalDate.of(2026, 7, 16));
    }

    @Test
    void aPageWithoutTheHoraireTableIsRefused() {
        assertThatThrownBy(() -> parser.parse("<html><body>الموقع في صيانة</body></html>"))
                .isInstanceOf(HabousParseException.class)
                .hasMessageContaining("horaire");
    }

    @Test
    void aTableWithNoDayRowsIsRefused() {
        String html = "<html><body><table id=\"horaire\">"
                + "<tr><th>الأيام</th><th>محرّم</th><th>يونيو / يوليوز</th><th>الصبح</th>"
                + "<th>الشروق</th><th>الظهر</th><th>العصر</th><th>المغرب</th><th>العشاء</th></tr>"
                + "</table>2026</body></html>";

        assertThatThrownBy(() -> parser.parse(html))
                .isInstanceOf(HabousParseException.class)
                .hasMessageContaining("no day rows");
    }

    @Test
    void aDecemberToJanuaryPageRollsTheYearOver() {
        // The wrap can cross a year boundary; the second month number is then
        // smaller than the first, and those rows belong to the following year.
        String html = table("دجنبر / يناير", 2026, new int[][] {{28}, {29}, {30}, {31}, {1}, {2}});

        List<HabousPublishedDay> days = parser.parse(html);

        assertThat(days.stream().map(HabousPublishedDay::date)).containsExactly(
                LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 29),
                LocalDate.of(2026, 12, 30), LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2));
    }

    @Test
    void aSingleMonthHeaderNeedsNoWrap() {
        String html = table("مارس", 2026, new int[][] {{3}, {4}, {5}});

        assertThat(parser.parse(html)).extracting(HabousPublishedDay::date).containsExactly(
                LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5));
    }

    @Test
    void anUnparseableTimeIsRefusedRatherThanGuessed() {
        String html = "<html><body><table id=\"horaire\">"
                + "<tr><th>الأيام</th><th>محرّم</th><th>مارس</th><th>الصبح</th><th>الشروق</th>"
                + "<th>الظهر</th><th>العصر</th><th>المغرب</th><th>العشاء</th></tr>"
                + "<tr><td>الأربعاء</td><td>1</td><td>3</td><td>--:--</td><td>06:13</td>"
                + "<td>13:33</td><td>17:14</td><td>20:45</td><td>22:19</td></tr>"
                + "</table>2026</body></html>";

        assertThatThrownBy(() -> parser.parse(html))
                .isInstanceOf(HabousParseException.class)
                .hasMessageContaining("Unparseable time");
    }

    @Test
    void anUnrecognizedMonthNameIsRefused() {
        assertThatThrownBy(() -> parser.parse(table("Floréal", 2026, new int[][] {{1}})))
                .isInstanceOf(HabousParseException.class);
    }

    /** Builds a minimal 9-column #horaire table with the given header and Gregorian days. */
    private static String table(String monthHeader, int year, int[][] days) {
        StringBuilder sb = new StringBuilder("<html><body><table id=\"horaire\">");
        sb.append("<tr><th>الأيام</th><th>محرّم</th><th>").append(monthHeader)
                .append("</th><th>الصبح</th><th>الشروق</th><th>الظهر</th>")
                .append("<th>العصر</th><th>المغرب</th><th>العشاء</th></tr>");
        for (int[] day : days) {
            sb.append("<tr><td>الأربعاء</td><td>1</td><td>").append(day[0]).append("</td>")
                    .append("<td>04:24</td><td>06:13</td><td>13:33</td>")
                    .append("<td>17:14</td><td>20:45</td><td>22:19</td></tr>");
        }
        return sb.append("</table>").append(year).append("</body></html>").toString();
    }
}
