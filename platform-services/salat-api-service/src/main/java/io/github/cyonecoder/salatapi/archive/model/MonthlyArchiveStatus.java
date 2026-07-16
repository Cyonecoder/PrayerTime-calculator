package io.github.cyonecoder.salatapi.archive.model;

/**
 * The state of a whole Gregorian monthly archive (spec 18).
 *
 * <p>The distinction that matters is {@link #PARTIAL} versus {@link #DEGRADED}: a
 * partial month is simply still accruing and expected to fill in, whereas a
 * degraded month has hit something that will not resolve on its own. An incomplete
 * archive is never labelled {@link #COMPLETE}.
 */
public enum MonthlyArchiveStatus {

    /** No validated rows exist for this month. */
    EMPTY,

    /**
     * Validated rows exist, but the complete Gregorian month has not yet been
     * assembled — the normal state while a month is still being collected across
     * successive Hijri pages, with no permanent failures.
     */
    PARTIAL,

    /** Every enabled official city has every Gregorian date of the month. */
    COMPLETE,

    /**
     * The month has data, but one or more cities failed permanently, or the
     * official catalogue changed during collection. Distinct from {@link #PARTIAL}
     * because the gap will not close by simply collecting more.
     */
    DEGRADED
}
