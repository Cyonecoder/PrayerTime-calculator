package io.github.cyonecoder.salatapi.archive.model;

/** How complete one city's schedule is within a Gregorian monthly archive. */
public enum CityArchiveStatus {
    /** Every calendar day of the month is present. */
    COMPLETE,
    /**
     * Some days are present, others are not. This is the <em>normal</em> state for
     * a month still being accumulated: one Hijri page only ever covers part of a
     * Gregorian month, so the rest arrives with a later page.
     */
    PARTIAL,
    /** No usable days at all; the city carries a failure code explaining why. */
    UNAVAILABLE
}
