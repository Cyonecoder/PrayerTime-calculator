package io.github.cyonecoder.salatapi.habous.validation;

import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Validates the parsed rows of an official timetable (spec 20).
 *
 * <p>Two things must hold before a page is trusted: the dates must be
 * <strong>contiguous</strong> (a gap means a row was dropped or misread), and
 * every row must carry all <strong>six</strong> prayer times (a missing column
 * would silently archive an incomplete day). The parser already refuses a
 * structurally broken table; this is the semantic check on what it produced.
 */
@Component
public class HabousTimetableValidator {

    /**
     * @param days the parsed days, in the order the parser produced them
     * @return a passing report, or the reasons the timetable was rejected
     */
    public HabousValidationReport validate(List<HabousPublishedDay> days) {
        List<String> problems = new ArrayList<>();

        if (days == null || days.isEmpty()) {
            return HabousValidationReport.rejected(List.of("timetable has no days"));
        }

        // Every column present for every day.
        for (HabousPublishedDay day : days) {
            List<String> missing = missingColumns(day);
            if (!missing.isEmpty()) {
                problems.add("day " + day.date() + " is missing " + String.join(", ", missing));
            }
        }

        // Strictly ascending, one day apart — no gaps, no duplicates, no reversals.
        for (int i = 1; i < days.size(); i++) {
            LocalDate previous = days.get(i - 1).date();
            LocalDate current = days.get(i).date();
            if (!current.equals(previous.plusDays(1))) {
                problems.add("dates are not contiguous: " + previous + " is followed by " + current);
            }
        }

        return problems.isEmpty()
                ? HabousValidationReport.ok()
                : HabousValidationReport.rejected(problems);
    }

    private List<String> missingColumns(HabousPublishedDay day) {
        List<String> missing = new ArrayList<>();
        checkColumn("fajr", day.fajr(), missing);
        checkColumn("sunrise", day.sunrise(), missing);
        checkColumn("dhuhr", day.dhuhr(), missing);
        checkColumn("asr", day.asr(), missing);
        checkColumn("maghrib", day.maghrib(), missing);
        checkColumn("isha", day.isha(), missing);
        return missing;
    }

    private void checkColumn(String name, LocalTime value, List<String> missing) {
        if (value == null) {
            missing.add(name);
        }
    }
}
