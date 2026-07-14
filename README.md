# PrayerTime Calculator (Morocco)

A small, well-tested Java library and CLI that computes Islamic prayer times for
Morocco, using the Moroccan (Habous) calculation convention, and can compare its
astronomical results against the official Habous timetable.

## Purpose

Given a Moroccan city and a date, produce the six daily times — Fajr, Sunrise,
Dhuhr, Asr, Maghrib, Isha — that match the officially published Moroccan
schedule as closely as the astronomy allows (within ~2 minutes for the five
prayers on the validated 2026 reference set). The engine is pure and
deterministic: it never reads the system clock or the JVM default time zone.

## Morocco method parameters

| Parameter            | Value        |
|----------------------|--------------|
| Fajr angle           | 19.0°        |
| Isha angle           | 17.0° (angle-based, **not** the Umm al-Qura 90-min rule) |
| Asr shadow factor    | 1.0 (majority/Shafi'i) |
| Sunrise/sunset base  | -0.8333° (plus an elevation dip) |
| Dhuhr adjustment     | +5 minutes   |
| Maghrib adjustment   | +5 minutes   |
| Other adjustments    | 0            |
| Default elevation    | Official city table (elevation 0) |

See `docs/calculation-methods.md` for the full rationale.

## Why `Africa/Casablanca` must be used

All civil offsets are resolved from the IANA time-zone database via
`ZoneId.of("Africa/Casablanca")`, evaluated at local noon. This is the single
source of truth for Morocco's clock, so:

- **No transition dates are hard-coded** — the code keeps working as future
  tzdata updates land.
- The JVM default time zone is never consulted, so results are identical on any
  machine.

### Morocco UTC+1 and the Ramadan UTC+0 suspension

Since 2018 Morocco stays on **permanent summer time (UTC+1)**, but reverts to
**UTC+0 for the duration of Ramadan**. The engine reflects this automatically:
for a date inside the Ramadan suspension the resolved offset is `+00:00`, and
outside it is `+01:00`. The offset is applied exactly once; there is no manual
"+60 minute" adjustment and no double shift. Because the tz database may model
the suspension with negative-DST semantics, the public clock label is derived
from the **total UTC offset**, not from `ZoneRules.isDaylightSavings()`.

## Installation

Requires a **Java 21** toolchain (Gradle will auto-provision one via the foojay
resolver if none is installed). No manual Gradle install is needed — use the
wrapper.

```bash
git clone https://github.com/Cyonecoder/PrayerTime-calculator.git
cd PrayerTime-calculator
```

## Build commands

```bash
./gradlew clean test    # run the unit/regression suite (excludes live tests)
./gradlew build         # test + Spotless style + JaCoCo coverage verification
./gradlew run --args="cities"
```

## CLI examples

```bash
# Single day
./gradlew run --args="calculate --city rabat --date 2026-07-03"

# Date range
./gradlew run --args="range --city rabat --from 2026-07-03 --to 2026-07-09"

# Whole month
./gradlew run --args="month --city casablanca --year 2026 --month 7"

# Civil-clock info (offset + mode + transitions)
./gradlew run --args="clock --date 2026-03-01"

# Compare with the official Habous timetable
./gradlew run --args="compare --city rabat --date 2026-07-03"

# List supported cities
./gradlew run --args="cities"
```

Every command prints the city, date, timezone, UTC offset, clock mode,
calculation method, source and the six times. Invalid cities or dates produce a
clear one-line error and a non-zero exit code (no stack trace).

## City support — two distinct notions

The registry bundles **67 cities** (the authoritative candidate inventory), each
with a stable slug, coordinates, default elevation, the `Africa/Casablanca` zone,
and aliases (Latin and Arabic, e.g. `fes`/`fez`, `طنجة`/`tanger`). Unknown cities
are **never** defaulted to Casablanca.

"Supported" means two different things — do not conflate them:

- **Registry support** (all 67) — the city resolves and can be computed with the
  global astronomical method. Having coordinates is *only* this.
- **Sec-approach support** (**48**) — the city is served by the city-corrected
  sec-approach. Granted **only** with a verified Habous mapping, a complete
  validated 180-observation dataset, accepted six-prayer offsets, and passing
  tests. A registry city without accepted offsets is **not** sec-supported and
  the sec-approach fails closed for it (no nearest-city / province / Casablanca
  fallback).

See [`docs/SEC_APPROACH.md`](docs/SEC_APPROACH.md) for the full story.

## Calculation vs. Habous sources

There are two distinct sources, kept strictly separate:

- **`astronomical`** — computed by this engine.
- **`habous-official`** — parsed from the official Habous timetable.

They are never merged into one result. The `compare` command reports the signed
per-prayer difference and a pass/fail against a configurable tolerance
(default 2 minutes for the five prayers). Sunrise is reported separately and does
not affect pass/fail. When Habous data is unavailable, the tool says so clearly
and does **not** silently substitute astronomical values — the caller chooses
the fallback.

## Precision limitations

- The five prayers match the validated 2026 Morocco reference within ~2 minutes;
  Maghrib is the weakest component (up to ~2 min early for some cities).
- **Sunrise** runs a systematic ~2–5 minutes late versus the official table and
  is therefore reported separately.
- Times are rounded to whole minutes.
- The solar model uses the original low-order series (declination / equation of
  time); it is accurate to about a minute, not sub-second.

## Live-test instructions

Tests that hit the real Habous website are tagged `live` and excluded from the
normal build. Run them explicitly (requires internet):

```bash
./gradlew liveTest
```

## Library usage example

```java
var registry = new MoroccoCityRegistry();
var calculator = new PrayerTimeCalculator();

City rabat = registry.requireById("rabat");
PrayerRequest request = PrayerRequest.of(
        LocalDate.of(2026, 7, 3),
        rabat.coordinates(),
        rabat.zone(),                 // Africa/Casablanca
        new MoroccoCalculationMethod());

PrayerTimes times = calculator.calculate(request);
System.out.println(times.dhuhr());    // 13:37
```

## Sec-approach (city-corrected) calculation

For the most accurate **offline** result, the `secaproach` command applies a
per-city, per-prayer constant-second offset to the reconstructed time
(parameter version `morocco-habous-secaproach-v2`). It reproduces the official
Habous timetable with **97.09% exact-minute agreement on the validated
2026-06-17 through 2026-07-16 reference window** (within ±1 minute for every
observation on that window), for the 48 sec-supported cities.

Those figures are measured on a single **30-day summer window** and stated as
exact-minute agreement on that window only — **not** a claim of annual or
year-round accuracy, and nothing is described as "100% accurate". The offsets are
*trained and verified* against Habous offline; the Habous timetable is a
**research/testing oracle only** and its daily values are never emitted as
production output. Full details, including how mappings were verified, how
offsets are trained (deterministic integer search), the
training/validation/holdout split, the accuracy gates, and per-city/per-prayer
results, are in [`docs/SEC_APPROACH.md`](docs/SEC_APPROACH.md).

## Further documentation

- [`docs/SEC_APPROACH.md`](docs/SEC_APPROACH.md) — the sec-approach: inventory,
  registry vs sec support, mapping verification, deterministic training, gates,
  honest accuracy, `v2` parameters.
- [`salat_commands.md`](salat_commands.md) — every runnable command, by category.
- `docs/architecture.md` — layers, dependency direction, provider design.
- `docs/calculation-methods.md` — method parameters, rounding, timezone policy.
- `docs/audit/` — the pre-refactor baseline and the legacy archive.
