package io.github.cyonecoder.salatapi.archive.model;

/** How complete a whole Gregorian monthly archive is. */
public enum MonthlyArchiveStatus {
    /** Every collected city has every day of the month. */
    COMPLETE,
    /** At least one city is missing at least one day; the month is still accruing. */
    PARTIAL,
    /** Nothing has been collected for this month. */
    EMPTY
}
