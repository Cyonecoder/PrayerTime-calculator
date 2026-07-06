# Final Validation Report

Complete final audit of the refactored PrayerTime-calculator. No defects were
found during verification; every required test passes. No features were added in
this step.

- **Date:** 2026-07-06
- **JDK:** Java 21 toolchain (Temurin 21.0.11, auto-provisioned)
- **Build:** Gradle 8.10.2 (wrapper)

## Architecture implemented

Layered, dependency-inverted design (see `docs/architecture.md`):

```
cli → application/service → validation → domain/{model,method,calculation} + time
infrastructure/{city,habous} depend inward on domain/model only
```

- Pure, deterministic engine (`domain/calculation`): Julian date → solar
  position → hour angles → time conversion → `PrayerTimeCalculator`.
- Civil time (`time`): offset + clock mode resolved from `Africa/Casablanca`.
- Providers kept separate: `astronomical` vs `habous-official`, compared (never
  merged) by `application/service`.
- CLI is the only layer that prints.

## Commands executed

- `./gradlew clean test` — **BUILD SUCCESSFUL**
- `./gradlew build` — **BUILD SUCCESSFUL** (Spotless + JaCoCo verification)
- `calculate` for rabat, casablanca, fez, marrakech, tangier, agadir (2026-07-03)
- `range --city rabat --from 2026-07-03 --to 2026-07-09`
- `clock` for 2026-02-14, 2026-02-15, 2026-03-21, 2026-03-22, 2026-07-03

All commands returned the expected output; invalid input yields a clean error
and a non-zero exit code (no stack trace).

## Test totals

| Metric | Value |
|--------|-------|
| Tests  | **173** |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 (live tests excluded from the normal task) |

## Coverage (JaCoCo, line)

| Scope | Coverage | Threshold |
|-------|----------|-----------|
| `domain.calculation` | 100.0% | ≥ 90% ✅ |
| `time` | 100.0% | ≥ 95% ✅ |
| `HabousHtmlParser` (class) | 100.0% | ≥ 90% ✅ |
| **Total project** | **86.8%** | ≥ 80% ✅ |
| `domain.model` / `validation` / `application.service` | 100.0% | — |
| `domain.method` | 92.9% | — |
| `infrastructure.city` | 91.7% | — |
| `infrastructure.habous` (package) | 73.5% | — (network paths untested by design) |
| `cli` | 66.9% | — |

## July 3–9 comparison table (Rabat: calculated vs cached official)

Signed = calculated − official (minutes). Five-prayer tolerance = 2 min.

| Date | Fajr | Dhuhr | Asr | Maghrib | Isha | Sunrise* | Max AE | Pass |
|------|-----:|------:|----:|--------:|-----:|---------:|-------:|:----:|
| 2026-07-03 | +0 | +0 | +0 | +1 | +1 | +3 | 1 | ✅ |
| 2026-07-04 | +1 | +0 | +0 | +1 | +0 | +2 | 1 | ✅ |
| 2026-07-05 | +0 | +0 | +0 | +0 | +0 | +3 | 0 | ✅ |
| 2026-07-06 | +0 | +0 | -1 | +0 | +1 | +2 | 1 | ✅ |
| 2026-07-07 | +0 | +0 | +0 | +1 | +0 | +3 | 1 | ✅ |
| 2026-07-08 | +1 | +0 | +0 | +1 | +0 | +2 | 1 | ✅ |
| 2026-07-09 | +0 | +0 | +0 | +1 | +0 | +3 | 1 | ✅ |

\* Sunrise is reported separately and does not affect pass/fail.

## City-by-city error metrics (2026-07-03 + Rabat week)

Per-prayer error across all 12 city/date reference entries:

| Prayer | MAE (min) | Max AE (min) |
|--------|----------:|-------------:|
| Fajr | 0.33 | 1 |
| Dhuhr | 0.00 | 0 |
| Asr | 0.17 | 1 |
| Maghrib | 0.83 | 2 |
| Isha | 0.17 | 1 |

Result per city (2026-07-03): rabat, casablanca, fez, marrakech, tangier,
agadir — **all PASS**. Overall regression: **PASS**.

## Summer-hour transition results

| Date | UTC offset | Clock mode |
|------|-----------|------------|
| 2026-02-14 | +01:00 | UTC_PLUS_ONE |
| 2026-02-15 | +00:00 | UTC_ZERO_RAMADAN_SUSPENSION |
| 2026-03-21 | +00:00 | UTC_ZERO_RAMADAN_SUSPENSION |
| 2026-03-22 | +01:00 | UTC_PLUS_ONE |
| 2026-07-03 | +01:00 | UTC_PLUS_ONE |

Across each transition, civil times shift by ~1 hour while the underlying UTC
solar instant stays continuous (offset applied exactly once — verified by
`ClockTransitionTest`). No manual +60 min adjustment exists in the engine.

## Verification checklist

| # | Requirement | Result |
|---|-------------|:------:|
| 1 | July 3 uses UTC+1 | ✅ |
| 2 | February 15 uses UTC+0 | ✅ |
| 3 | March 22 uses UTC+1 | ✅ |
| 4 | No double one-hour adjustment | ✅ |
| 5 | January and February correct | ✅ |
| 6 | Fajr uses 19° | ✅ |
| 7 | Isha uses 17° | ✅ |
| 8 | Asr uses factor 1 | ✅ |
| 9 | Results identify their source | ✅ |
| 10 | Habous failures don't corrupt calculated results | ✅ |
| 11 | All tests deterministic | ✅ |
| 12 | No unit test requires internet | ✅ |
| 13 | No NaN/infinity/invalid ordering in 2026 annual tests | ✅ |
| 14 | No hard-coded date in production calculations | ✅ (year-boundary math uses the input year only) |
| 15 | No unknown city silently becomes Casablanca | ✅ |
| 16 | No TLS validation disabled | ✅ |

## Remaining limitations

- Five prayers match the cached 2026 official set within 2 minutes; **Maghrib**
  is the weakest component (up to ~2 min early for Fez/Marrakech).
- **Sunrise** runs a systematic ~2–5 min late vs the official table; reported
  separately, excluded from pass/fail.
- Times are rounded to whole minutes; the low-order solar series is accurate to
  ~1 minute, not sub-second.
- The bundled cities carry no real Habous IDs yet, so `compare` reports
  "unavailable" until IDs are supplied (calculated results are never
  substituted).

## Live Habous status

Live tests are tagged `live` and excluded from `test`/CI. They are **not** run in
this audit (no network dependency). Run manually with `./gradlew liveTest`.
Normal CI passes even when Habous is offline.

## Exact files changed in this audit

- `docs/audit/final-validation.md` (this report) — added.

No production or test code required changes: verification found no defects.

## Final git status

Working tree clean after committing this report (see the repository log; the
final commit is `fix: complete Morocco prayer-engine validation`).
