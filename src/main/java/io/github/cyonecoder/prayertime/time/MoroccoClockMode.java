package io.github.cyonecoder.prayertime.time;

/**
 * The civil-clock regime in effect in Morocco on a given day.
 *
 * <p>Since 2018 Morocco stays on permanent summer time (UTC+1) but reverts to
 * UTC+0 for the duration of Ramadan. The mode is derived from the actual total
 * UTC offset reported by the IANA time-zone database, never from hard-coded
 * dates.
 */
public enum MoroccoClockMode {

    /** Standard Moroccan civil time: total UTC offset of +01:00. */
    UTC_PLUS_ONE,

    /** Ramadan clock suspension: total UTC offset of +00:00. */
    UTC_ZERO_RAMADAN_SUSPENSION,

    /**
     * Any other total offset (e.g. historical values before permanent summer
     * time). Treated as unexpected by the civil-time service.
     */
    OTHER
}
