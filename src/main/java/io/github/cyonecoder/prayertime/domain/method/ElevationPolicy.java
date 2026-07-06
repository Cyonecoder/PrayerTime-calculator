package io.github.cyonecoder.prayertime.domain.method;

/**
 * How elevation is fed into a calculation method.
 *
 * <p>The horizon dip used for sunrise/sunset/Maghrib depends on the observer's
 * height. This policy selects where that height comes from.
 */
public enum ElevationPolicy {

    /**
     * Use the elevation baked into the official city table (the value the
     * reference authority published its times against). Chosen so results
     * match the published schedule.
     */
    OFFICIAL_CITY_TABLE,

    /**
     * Use the actual observer/coordinate elevation supplied in the request.
     */
    OBSERVER_ELEVATION
}
