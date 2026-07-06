# Calculation Methods

This document describes the Moroccan (Habous) method parameters and the engine's
rounding and timezone policies. Method-specific values live in
`MoroccoCalculationMethod` / `CalculationParameters`; the generic engine contains
no country-specific conditions.

## Twilight angles

- **Fajr: 19.0°** — the sun is 19° below the horizon at the start of Fajr.
- **Isha: 17.0°** — angle-based. The Umm al-Qura fixed 90-minute Maghrib→Isha
  rule is deliberately **not** used, as it does not match Morocco.

Angles are validated to lie strictly within (0°, 90°).

## Asr

- **Standard shadow factor: 1.0** (majority / Shafi'i). The Asr sun altitude is
  derived from `acot(shadowFactor + tan(|latitude − declination|))`. The school
  is selected purely by this configurable factor — there is no hard-coded value.
  (A factor of 2 would give the Hanafi time.)

## Dhuhr and Maghrib adjustments

- **Dhuhr: +5 minutes**, **Maghrib: +5 minutes**.

These buffers are **method-level configuration** (`PrayerAdjustments`), applied
to the raw astronomical result — they are not baked into the astronomy. This
keeps the engine method-agnostic and the buffers easy to tune. All other
adjustments (Fajr, Sunrise, Asr, Isha) are 0.

## Sunrise / sunset assumptions

- **Base horizon angle: -0.8333°**, accounting for the solar radius and mean
  refraction at the horizon.
- An **elevation dip** of `-0.0347 × √(elevation_metres)` is subtracted when the
  elevation policy provides a positive elevation. Under the default
  `OFFICIAL_CITY_TABLE` policy the elevation is 0, so no dip is applied and
  results match the published city table.
- Sunrise is a *morning* event (before solar noon); sunset/Maghrib are *evening*
  events (after). Sunrise is published for information and is validated/compared
  separately from the five obligatory prayers.

## Rounding policy

- Final times are **rounded (not truncated) to the nearest whole minute** via
  `TimeConversion`, which also normalizes any value into `00:00`–`23:59` using
  floor-modulo (negative or ≥24h inputs wrap into a single day). All time
  formatting flows through this one component — there is no duplicated
  hours/minutes/seconds arithmetic scattered across the code.

## Timezone policy

- The civil UTC offset is resolved **once per request** from
  `Africa/Casablanca`, evaluated at local noon (never inside a transition
  gap/overlap).
- That single offset is used to obtain the Julian date in Universal Time and to
  express solar noon in civil time. It is applied exactly once — there is no
  second "+60 minute" summer-time adjustment.
- Morocco observes permanent UTC+1 with a UTC+0 suspension during Ramadan; both
  are read from the tz database, never from hard-coded dates. See
  `docs/architecture.md` for the summer-hours module design.
