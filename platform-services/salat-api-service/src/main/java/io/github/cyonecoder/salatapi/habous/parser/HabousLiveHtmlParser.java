package io.github.cyonecoder.salatapi.habous.parser;

import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

/**
 * Parses the live Habous monthly timetable, whose {@code #horaire} table is
 * indexed by the <strong>Hijri</strong> month and has nine columns:
 * {@code [weekday, hijriDay, gregorianDay, Fajr, Sunrise, Dhuhr, Asr, Maghrib,
 * Isha]}.
 *
 * <p><strong>The Hijri/Gregorian wrap.</strong> A Hijri month straddles two
 * Gregorian months, so the Gregorian-day column ascends and then wraps (e.g.
 * 17…30, 1…16). This parser detects that wrap and reads the two Gregorian month
 * names from the column header (e.g. "يونيو / يوليوز") to assign every row a
 * correct, unambiguous {@link LocalDate} — including the year rollover when the
 * page straddles December/January.
 *
 * <p>This is why a single fetched page contributes days to <em>two</em> Gregorian
 * monthly archives, and why no single page ever completes a Gregorian month.
 *
 * <p>Ported verbatim from
 * {@code infrastructure.habous.HabousLiveHtmlParser}; see
 * {@code docs/EXTRACTION_MAP.md}. The seven-column {@code HabousHtmlParser} was a
 * separate parser for a different layout and is not part of the live path, so it
 * is not ported.
 */
@Component
public class HabousLiveHtmlParser {

    /** Parser identifier recorded as provenance on every stored snapshot. */
    public static final String PARSER_VERSION = "habous-live-1";

    private static final String TABLE_ID = "horaire";
    private static final int EXPECTED_COLUMNS = 9;
    private static final int GREGORIAN_DAY_COLUMN = 2;
    private static final int FIRST_TIME_COLUMN = 3;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");
    private static final Pattern YEAR = Pattern.compile("\\b(20\\d{2})\\b");

    /** Moroccan-Arabic Gregorian month names → month number, including common variants. */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("يناير", 1),
            Map.entry("فبراير", 2),
            Map.entry("مارس", 3),
            Map.entry("أبريل", 4), Map.entry("إبريل", 4), Map.entry("ابريل", 4),
            Map.entry("ماي", 5), Map.entry("مايو", 5),
            Map.entry("يونيو", 6), Map.entry("يونيه", 6),
            Map.entry("يوليوز", 7), Map.entry("يوليو", 7),
            Map.entry("غشت", 8), Map.entry("أغسطس", 8),
            Map.entry("شتنبر", 9), Map.entry("سبتمبر", 9),
            Map.entry("أكتوبر", 10), Map.entry("اكتوبر", 10),
            Map.entry("نونبر", 11), Map.entry("نوفمبر", 11),
            Map.entry("دجنبر", 12), Map.entry("ديسمبر", 12));

    /**
     * Parses every day row from the live {@code #horaire} table, resolving each to
     * an absolute Gregorian date.
     *
     * @param html the full HTML page
     * @return the parsed days, in table (chronological) order
     * @throws HabousParseException if the table, header or a data row is invalid
     */
    public List<HabousPublishedDay> parse(String html) {
        Objects.requireNonNull(html, "html");
        Document document = Jsoup.parse(html);
        Element table = document.getElementById(TABLE_ID);
        if (table == null) {
            throw new HabousParseException("No table with id=\"" + TABLE_ID + "\" found");
        }

        int[] months = gregorianMonths(table);
        int year = year(document);

        List<HabousPublishedDay> days = new ArrayList<>();
        int monthIndex = 0;              // 0 = first Gregorian month, 1 = second
        int previousDay = Integer.MIN_VALUE;
        for (Element row : table.select("tr")) {
            Elements cells = row.select("td");
            if (cells.size() < EXPECTED_COLUMNS) {
                continue; // header row (<th>) or spacer
            }
            Integer gregorianDay = tryParseDay(cells.get(GREGORIAN_DAY_COLUMN).text());
            if (gregorianDay == null) {
                continue; // not a data row (e.g. the column-title row)
            }
            if (previousDay != Integer.MIN_VALUE && gregorianDay < previousDay) {
                monthIndex++; // Gregorian day wrapped: advance to the next month
            }
            if (monthIndex > 1) {
                throw new HabousParseException(
                        "Gregorian day wrapped more than once; unexpected table shape");
            }
            previousDay = gregorianDay;

            int month = months[monthIndex];
            // A Dec→Jan page wraps the year too: the second month number is smaller.
            int rowYear = monthIndex == 1 && months[1] < months[0] ? year + 1 : year;
            LocalDate date = safeDate(rowYear, month, gregorianDay);
            days.add(new HabousPublishedDay(
                    date,
                    parseTime(cells.get(FIRST_TIME_COLUMN).text()),
                    parseTime(cells.get(FIRST_TIME_COLUMN + 1).text()),
                    parseTime(cells.get(FIRST_TIME_COLUMN + 2).text()),
                    parseTime(cells.get(FIRST_TIME_COLUMN + 3).text()),
                    parseTime(cells.get(FIRST_TIME_COLUMN + 4).text()),
                    parseTime(cells.get(FIRST_TIME_COLUMN + 5).text())));
        }
        if (days.isEmpty()) {
            throw new HabousParseException("Table #" + TABLE_ID + " contained no day rows");
        }
        return days;
    }

    // --- Header parsing ------------------------------------------------------

    /**
     * Reads the one or two Gregorian month names from the Gregorian-day column
     * header (e.g. "يونيو / يوليوز") and returns their month numbers. When only one
     * month is present both entries are that month.
     */
    private int[] gregorianMonths(Element table) {
        String headerText = columnHeaderText(table);
        String[] parts = headerText.split("/");
        if (parts.length == 1) {
            int m = monthNumber(parts[0]);
            return new int[] {m, m};
        }
        return new int[] {monthNumber(parts[0]), monthNumber(parts[1])};
    }

    /** The text of the Gregorian-day column header cell. */
    private String columnHeaderText(Element table) {
        for (Element row : table.select("tr")) {
            Elements headerCells = row.select("th");
            Elements cells = headerCells.isEmpty() ? row.select("td") : headerCells;
            if (cells.size() >= EXPECTED_COLUMNS) {
                String text = cells.get(GREGORIAN_DAY_COLUMN).text().trim();
                // The header cell contains month names; a data row contains a number.
                if (tryParseDay(text) == null && containsAnyMonth(text)) {
                    return text;
                }
            }
        }
        throw new HabousParseException("Could not find the Gregorian month header in #" + TABLE_ID);
    }

    private boolean containsAnyMonth(String text) {
        for (String name : MONTHS.keySet()) {
            if (text.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private int monthNumber(String rawName) {
        String name = rawName.trim();
        Integer direct = MONTHS.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, Integer> e : MONTHS.entrySet()) {
            if (name.contains(e.getKey())) {
                return e.getValue();
            }
        }
        throw new HabousParseException("Unrecognized Gregorian month name: '" + rawName + "'");
    }

    private int year(Document document) {
        Matcher m = YEAR.matcher(document.text());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        throw new HabousParseException("Could not find a four-digit year on the page");
    }

    // --- Cell parsing --------------------------------------------------------

    private Integer tryParseDay(String text) {
        String value = text.trim();
        if (!value.matches("\\d{1,2}")) {
            return null;
        }
        int day = Integer.parseInt(value);
        return (day >= 1 && day <= 31) ? day : null;
    }

    private LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            throw new HabousParseException(String.format(Locale.ROOT,
                    "Invalid Gregorian date %04d-%02d-%02d: %s", year, month, day, e.getMessage()));
        }
    }

    private LocalTime parseTime(String text) {
        String value = text.trim();
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new HabousParseException("Unparseable time: '" + text + "'");
        }
    }
}
