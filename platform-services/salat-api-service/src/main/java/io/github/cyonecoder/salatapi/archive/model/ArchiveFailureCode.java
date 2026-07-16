package io.github.cyonecoder.salatapi.archive.model;

/**
 * Why a city has no usable schedule in a monthly archive.
 *
 * <p>A failed city is <strong>listed</strong> with its code, never omitted: an
 * absent city is indistinguishable from a city that was never official, whereas a
 * declared failure is auditable and fixable.
 */
public enum ArchiveFailureCode {
    /** The official timetable could not be retrieved (outage, timeout, non-200). */
    TIMETABLE_UNAVAILABLE,
    /** A page arrived but was not a trustworthy timetable, so it was rejected. */
    PAGE_UNPARSEABLE,
    /** The city is official and collectable, but no attempt has covered this month yet. */
    NOT_YET_COLLECTED
}
