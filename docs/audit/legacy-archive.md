# Legacy Implementation — Archive

The original single-file prayer calculator (`LegacyMain`, formerly `src/Main.java`)
has been **removed** now that the corrected, modular engine is complete and
validated against official Morocco (Habous) reference values
(see `docs/audit/baseline.md` and the July 2026 regression suite).

This file archives what the legacy code did, replacing the live
characterization test that previously pinned its behaviour.

## Archived output (Casablanca, 2024-03-17 23:59:59 UTC)

With the original hard-coded inputs (`LONG=-6.7480954`, `LAT=34.0361152`,
`ELEVATION=96`, `FAJR_ANGLE=18.5`, `ISHA_ANGLE=17`, `Z=0`):

```
AL FAJR     17/3/2024 ==> 05:07:23
SunRize     17/3/2024 ==> 06:31:36
AL DUHR     17/3/2024 ==> 12:41:01
AL Asr      17/3/2024 ==> 16:00:59
AL MAGHRIB  17/3/2024 ==> 18:41:55
AL ISHA     17/3/2024 ==> 19:55:15
```

These are **not** official Morocco reference times; they are the legacy
formula's raw output, retained only for historical comparison. The corrected
engine intentionally differs (e.g. it uses the Morocco 19°/17° method, a +5 min
Dhuhr buffer, and a civil offset resolved from `Africa/Casablanca`).

## Why it was removed

- All calculation logic was extracted into pure, tested components
  (`JulianDateCalculator`, `SolarPositionCalculator`, `HourAngleCalculator`,
  `TimeConversion`, `PrayerTimeCalculator`).
- The legacy class held business logic in `main()` and printed directly to
  stdout, which is now reserved for CLI classes only.
- Its Julian-date branch was incorrect for January/February; the replacement is
  covered by dedicated tests.

The corrected engine's independence from this archived value is still asserted
by `PrayerTimeCalculatorTest.correctedEngineDiffersFromArchivedLegacyValue`.
