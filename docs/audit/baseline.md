# Legacy Prayer Calculator — Baseline Audit

This document captures the state of the project **before** any refactoring, so
the behaviour of the legacy implementation can be compared against future work.

- **Date captured:** 2026-07-06
- **Branch:** `refactor/morocco-prayer-engine` (created from `main`)
- **Baseline commit on `main`:** `452a796` — "calculation for prayer time Morocco"
- **JDK:** OpenJDK 17.0.15 (Homebrew), `javac` 17.0.15

## Repository layout (baseline)

```
.
├── .gitignore
├── .idea/                 (tracked IDE files — pre-existing)
├── projectprayercalc.iml  (tracked IDE file — pre-existing)
├── .claude/settings.local.json
└── src/
    └── Main.java          (single-class implementation)
```

The entire program is one file, `src/Main.java`, with all inputs hard-coded and
all logic in `main()`.

## How to build and run

### Compilation command
```
javac src/Main.java
```
Produces `src/Main.class` (a generated binary — now ignored via `.gitignore`,
not tracked).

### Runtime command
```
java -cp src Main
```

Compilation status: **SUCCESS** (exit code 0, no warnings emitted).

## Current hard-coded inputs

All inputs are literals in `main()`:

| Variable      | Value          | Meaning                                        |
|---------------|----------------|------------------------------------------------|
| `Y`           | 2024           | Year                                           |
| `M`           | 3              | Month                                          |
| `D`           | 17             | Day                                            |
| `H`           | 23             | Hour                                           |
| `m`           | 59             | Minute                                         |
| `s`           | 59             | Second                                         |
| `Z`           | 0              | UTC offset                                     |
| `SF`          | 1              | Shadow length factor (Asr)                     |
| `LONG`        | -6.7480954     | Longitude (Casablanca)                         |
| `ELEVATION`   | 96             | Elevation above sea level (m), Casablanca      |
| `LAT`         | 34.0361152     | Latitude (Casablanca)                          |
| `FAJR_ANGLE`  | 18.5           | Fajr twilight angle (degrees)                  |
| `ISHA_ANGLE`  | 17             | Isha twilight angle (degrees)                  |

## Complete current output

Running `java -cp src Main` produces exactly:

```
AL FAJR     17/3/2024 ==> 05:07:23
SunRize     17/3/2024 ==> 06:31:36
AL DUHR     17/3/2024 ==> 12:41:01
AL Asr      17/3/2024 ==> 16:00:59
AL MAGHRIB  17/3/2024 ==> 18:41:55
AL ISHA     17/3/2024 ==> 19:55:15
```

## Known technical problems

Recorded for the refactor; **not fixed in this baseline commit**:

1. **No separation of concerns.** All input, astronomical calculation,
   time conversion, formatting and output live in a single `main()` method.
2. **Hard-coded inputs.** Date, location and angles are literals; there is no
   way to pass a city, date, or calculation method without editing source.
3. **No project/build system.** No Gradle/Maven; only ad-hoc `javac`. No
   dependency management, no package structure (default package).
4. **No tests.** There is no automated verification and no reference fixtures
   to guard against regressions.
5. **Magic numbers / unexplained constants.** Empirical corrections
   (e.g. Duhr `+6 min`, Maghrib `+3m30s`, Fajr/Isha angles) are embedded
   without documentation of their source or the Morocco (Habous) convention.
6. **Isha calculation is known-questionable.** A source comment notes the
   Umm al-Qura 90-minute Maghrib→Isha rule "doesn't work correctly for
   Morocco"; the current angle-based value is provisional.
7. **No timezone / civil-time handling.** `Z` is a fixed integer offset with no
   handling of Morocco's DST / Ramadan clock changes.
8. **Truncation instead of rounding.** Times are truncated to seconds via
   `(int)` casts rather than rounded, and there is no consistent rounding
   policy for published minute-level times.
9. **Tracked IDE artefacts.** `.idea/` and `*.iml` were committed historically;
   now added to `.gitignore` (existing tracked copies left in place to keep the
   baseline diff limited to documentation and ignore-file changes).

## Validation of this baseline

- Existing code still compiles (`javac src/Main.java`, exit 0).
- Existing output is unchanged (no source edits made).
- `git diff` contains only documentation (`docs/audit/baseline.md`) and
  ignore-file (`.gitignore`) changes.
- No generated binary is tracked (`src/Main.class` is untracked and ignored).
