package io.github.cyonecoder.prayertime.infrastructure.habous;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Parses the monthly prayer-time table from a Habous timetable HTML page.
 *
 * <p>The relevant table has {@code id="horaire"} and one row per Gregorian day
 * with seven cells: day-of-month, Fajr, Sunrise (Chourouk), Dhuhr, Asr,
 * Maghrib and Isha. Malformed rows are rejected rather than guessed at.
 */
public final class HabousHtmlParser {

    private static final String TABLE_ID = "horaire";
    private static final int EXPECTED_COLUMNS = 7;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

    /**
     * One Gregorian day's official times, as published by Habous.
     *
     * @param gregorianDay day-of-month (1..31)
     */
    public record HabousDay(
            int gregorianDay,
            LocalTime fajr,
            LocalTime sunrise,
            LocalTime dhuhr,
            LocalTime asr,
            LocalTime maghrib,
            LocalTime isha) {
    }

    /** Raised when the Habous HTML cannot be parsed into a valid month table. */
    public static final class HabousParseException extends RuntimeException {
        public HabousParseException(String message) {
            super(message);
        }
    }

    /**
     * Parses every day row from the {@code #horaire} table.
     *
     * @param html the full HTML page
     * @return the parsed days, in table order
     * @throws NullPointerException  if {@code html} is {@code null}
     * @throws HabousParseException  if the table is missing or a data row is
     *                               malformed
     */
    public List<HabousDay> parse(String html) {
        Objects.requireNonNull(html, "html");
        Document document = Jsoup.parse(html);
        Element table = document.getElementById(TABLE_ID);
        if (table == null) {
            throw new HabousParseException("No table with id=\"" + TABLE_ID + "\" found");
        }

        List<HabousDay> days = new ArrayList<>();
        for (Element row : table.select("tr")) {
            Elements cells = row.select("td");
            if (cells.isEmpty()) {
                // Header row (uses <th>) or spacer — skip.
                continue;
            }
            if (cells.size() < EXPECTED_COLUMNS) {
                throw new HabousParseException(
                        "Malformed row: expected " + EXPECTED_COLUMNS + " cells but got "
                                + cells.size() + " -> " + row.text());
            }
            days.add(new HabousDay(
                    parseDay(cells.get(0).text()),
                    parseTime(cells.get(1).text()),
                    parseTime(cells.get(2).text()),
                    parseTime(cells.get(3).text()),
                    parseTime(cells.get(4).text()),
                    parseTime(cells.get(5).text()),
                    parseTime(cells.get(6).text())));
        }
        if (days.isEmpty()) {
            throw new HabousParseException("Table #" + TABLE_ID + " contained no day rows");
        }
        return days;
    }

    private int parseDay(String text) {
        try {
            int day = Integer.parseInt(text.trim());
            if (day < 1 || day > 31) {
                throw new HabousParseException("Invalid day-of-month: " + text);
            }
            return day;
        } catch (NumberFormatException e) {
            throw new HabousParseException("Unparseable day-of-month: '" + text + "'");
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
