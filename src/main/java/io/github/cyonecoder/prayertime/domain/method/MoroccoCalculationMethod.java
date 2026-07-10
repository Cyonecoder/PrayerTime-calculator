package io.github.cyonecoder.prayertime.domain.method;

/**
 * The Moroccan (Habous) calculation method.
 *
 * <p>Initial parameter set:
 * <ul>
 *   <li>Fajr angle: 19.0°</li>
 *   <li>Isha angle: 17.0° (angle-based; the Umm al-Qura fixed 90-minute Isha
 *       rule is deliberately <strong>not</strong> used here)</li>
 *   <li>Asr shadow factor: 1.0</li>
 *   <li>Sunrise/sunset base angle: -0.8333°</li>
 *   <li>Dhuhr adjustment: +5 minutes</li>
 *   <li>Maghrib adjustment: +5 minutes</li>
 *   <li>All other adjustments: 0</li>
 *   <li>Default elevation policy: {@link ElevationPolicy#OFFICIAL_CITY_TABLE}</li>
 * </ul>
 *
 * <p>The Dhuhr and Maghrib buffers are intentionally expressed as method-level
 * {@link PrayerAdjustments} rather than being hard-wired into the astronomy, so
 * the generic engine remains method-agnostic and the buffers stay configurable.
 */
public final class MoroccoCalculationMethod implements CalculationMethod {

    private static final String ID = "morocco";
    private static final String DISPLAY_NAME = "Morocco (Habous)";

    private static final double FAJR_ANGLE = 19.0;
    private static final double ISHA_ANGLE = 17.0;
    private static final double ASR_SHADOW_FACTOR = 1.0;
    private static final double SUNRISE_BASE_ANGLE = -0.8333;
    private static final int DHUHR_BUFFER_MINUTES = 5;
    private static final int MAGHRIB_BUFFER_MINUTES = 5;

    private static final CalculationParameters PARAMETERS = new CalculationParameters(
            FAJR_ANGLE,
            ISHA_ANGLE,
            ASR_SHADOW_FACTOR,
            SUNRISE_BASE_ANGLE,
            new PrayerAdjustments(
                    0,                       // fajr
                    0,                       // sunrise
                    DHUHR_BUFFER_MINUTES,    // dhuhr
                    0,                       // asr
                    MAGHRIB_BUFFER_MINUTES,  // maghrib
                    0),                      // isha
            ElevationPolicy.OFFICIAL_CITY_TABLE);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public CalculationParameters parameters() {
        return PARAMETERS;
    }
}
