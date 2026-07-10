package io.github.cyonecoder.prayertime.audit.dataset;

import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Local HTML fixtures and test doubles for the Habous collection tests. No
 * network is ever used here.
 */
final class HabousTestFixtures {

    private HabousTestFixtures() {
    }

    /** Builds a valid {@code #horaire} page for a whole month, in the given Arabic name/year. */
    static String monthHtml(String arabicName, int year, int month) {
        return monthHtml(arabicName, year, month, YearMonth.of(year, month).lengthOfMonth());
    }

    /** Builds a page containing only {@code daysToInclude} day rows (for incomplete-table tests). */
    static String monthHtml(String arabicName, int year, int month, int daysToInclude) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"ar\" dir=\"rtl\">\n<head><meta charset=\"utf-8\">")
                .append("<title>Habous</title></head>\n<body>\n");
        sb.append("<h1>مواقيت الصلاة - ").append(arabicName).append(" - ").append(year).append("</h1>\n");
        sb.append("<table id=\"horaire\" border=\"1\">\n<thead>\n")
                .append("<tr><th>اليوم</th><th>الفجر</th><th>الشروق</th><th>الظهر</th>")
                .append("<th>العصر</th><th>المغرب</th><th>العشاء</th></tr>\n</thead>\n<tbody>\n");
        for (int d = 1; d <= daysToInclude; d++) {
            sb.append(row(d));
        }
        sb.append("</tbody>\n</table>\n</body>\n</html>\n");
        return sb.toString();
    }

    /** A page with the header (Arabic name + year) but a broken table row. */
    static String malformedTableHtml(String arabicName, int year, int month) {
        return "<html><body><h1>مواقيت الصلاة - " + arabicName + " - " + year + "</h1>"
                + "<table id=\"horaire\"><tbody>"
                + "<tr><td>1</td><td>05:01</td><td>06:01</td></tr>"  // too few cells
                + "</tbody></table></body></html>";
    }

    private static String row(int day) {
        return String.format(Locale.ROOT,
                "<tr><td>%d</td><td>05:%02d</td><td>06:%02d</td><td>13:%02d</td>"
                        + "<td>17:%02d</td><td>20:%02d</td><td>22:%02d</td></tr>%n",
                day, day, day, day, day, day, day);
    }

    /** A fetcher backed by an in-memory {@code habousCityId -> html} function, counting calls. */
    static final class RecordingFetcher implements Fetcher {
        private final BiFunction<String, LocalDate, String> responder;
        final AtomicInteger calls = new AtomicInteger();

        RecordingFetcher(BiFunction<String, LocalDate, String> responder) {
            this.responder = responder;
        }

        @Override
        public String fetchMonthlyHtml(String habousCityId, LocalDate date) {
            calls.incrementAndGet();
            return responder.apply(habousCityId, date);
        }
    }

    /** A fetcher that throws the first {@code failures} times, then returns {@code html}. */
    static final class FlakyFetcher implements Fetcher {
        private final int failures;
        private final String html;
        final AtomicInteger calls = new AtomicInteger();

        FlakyFetcher(int failures, String html) {
            this.failures = failures;
            this.html = html;
        }

        @Override
        public String fetchMonthlyHtml(String habousCityId, LocalDate date) {
            int attempt = calls.incrementAndGet();
            if (attempt <= failures) {
                throw new HabousUnavailableException("simulated transient failure #" + attempt);
            }
            return html;
        }
    }

    /** A sleeper that never actually sleeps but records how many delays were requested. */
    static final class RecordingSleeper implements HabousReferenceCollector.Sleeper {
        final AtomicInteger sleeps = new AtomicInteger();

        @Override
        public void sleep(java.time.Duration duration) {
            sleeps.incrementAndGet();
        }
    }
}
