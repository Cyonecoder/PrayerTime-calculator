package io.github.cyonecoder.salatapi.archive.model;

/**
 * Why a city has no usable schedule in a monthly archive.
 *
 * <p>A failed city is <strong>listed</strong> with its code, never omitted: an
 * absent city is indistinguishable from a city that was never official, whereas a
 * declared failure is auditable and fixable.
 *
 * <p>{@link #permanent()} distinguishes a failure that will not resolve by
 * collecting again (which degrades the month, spec 18) from a transient one that a
 * later sync may recover.
 */
public enum ArchiveFailureCode {

    /**
     * The official timetable could not be retrieved (outage, timeout, non-200).
     * Transient: the site may be reachable on a later sync, so the month stays
     * partial rather than degraded.
     */
    TIMETABLE_UNAVAILABLE(false),

    /**
     * A page arrived but was not a trustworthy timetable, so it was rejected.
     * Permanent: re-fetching identical malformed content cannot make it parse
     * (spec 14 forbids retrying parse failures), so this degrades the month.
     */
    PAGE_UNPARSEABLE(true),

    /**
     * The city is official and collectable, but no attempt has covered this month
     * yet. Not a failure at all — the normal state of a month still accruing.
     */
    NOT_YET_COLLECTED(false);

    private final boolean permanent;

    ArchiveFailureCode(boolean permanent) {
        this.permanent = permanent;
    }

    /** @return whether this failure will not resolve by collecting again */
    public boolean permanent() {
        return permanent;
    }
}
