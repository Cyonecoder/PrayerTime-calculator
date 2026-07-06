# Architecture

The project follows a layered, dependency-inverted design. All computation lives
in pure domain code; infrastructure and the CLI sit at the edges.

## Layers and responsibilities

```
cli                → argument parsing + presentation (the only layer that prints)
application/service → orchestration: comparison of sources, reporting
validation          → consistency & tolerance checks over results
domain/model        → immutable value objects (Coordinates, City, PrayerTimes, …)
domain/method       → calculation methods & their parameters (Morocco/Habous)
domain/calculation  → pure astronomy (Julian date, solar position, hour angles,
                      time conversion, the PrayerTimeCalculator engine)
time                → Morocco civil-time resolution (offset + clock mode)
infrastructure/city → bundled city registry (JSON resource)
infrastructure/habous → official Habous adapter (HTTP client, HTML parser, cache)
```

| Layer                 | Responsibility                         | May depend on                         |
| --------------------- | -------------------------------------- | ------------------------------------- |
| `domain/model`        | Immutable data + invariants            | nothing (JDK only)                    |
| `domain/method`       | Method identity + parameters           | `domain/model`                        |
| `domain/calculation`  | Deterministic math engine              | `domain/model`, `domain/method`       |
| `time`                | Civil offset/mode from tzdata          | JDK time only                         |
| `validation`          | Ordering/relationship/tolerance checks | `domain/model`, `application/service` |
| `application/service` | Compare calculated vs official         | `domain/model`                        |
| `infrastructure/*`    | I/O adapters                           | `domain/model` (+ libraries)          |
| `cli`                 | Parse args, call services, print       | everything below                      |

## Dependency direction

Dependencies point **inward**: infrastructure and CLI depend on the domain, never
the reverse. The domain has no knowledge of HTTP, HTML, JSON, Gradle, or the CLI.
The engine takes explicit inputs (`PrayerRequest`) and returns immutable results;
it reads no global mutable state, no system clock, and no default time zone.

## Provider architecture

Prayer times come from independent providers:

- **Astronomical** — the `PrayerTimeCalculator` engine.
- **Habous official** — `HabousPrayerTimesProvider`, composed of a
  `HabousClient` (JDK `HttpClient`, timeouts, clear User-Agent, TLS never
  disabled), a `HabousHtmlParser` (the `#horaire` table), and a `HabousCache`
  (monthly responses with provenance metadata). The provider is handed a
  `HabousClient.Fetcher` (a functional interface) so tests inject fixture HTML
  and never touch the network.

When Habous data is unavailable (no Habous ID, 5xx/outage, unparseable HTML, or
the requested day absent) the provider returns `Optional.empty()` rather than
throwing or guessing.

## Why Habous and calculation outputs stay separate

Merging an official timetable with computed values would hide disagreements and
misrepresent provenance. Instead:

- Each result carries its own `source` label (`astronomical` vs
  `habous-official`) and is never combined into a single `PrayerTimes`.
- `PrayerTimeComparisonService` compares them and returns either a `Compared`
  report or an explicit `OfficialUnavailable` outcome.
- The caller — not the library — decides whether to fall back to astronomical
  times when Habous is unavailable.

## Summer-hours module design

`MoroccoCivilTimeService` resolves the offset for a date at **local noon** (never
inside a transition gap/overlap) straight from `Africa/Casablanca` rules. It maps
the total offset to a `MoroccoClockMode` (`UTC_PLUS_ONE`,
`UTC_ZERO_RAMADAN_SUSPENSION`, or `OTHER`) and refuses to guess for unexpected
offsets. No legal transition dates are stored in code; the tz database is the
single source of truth. The engine applies the resolved offset exactly once.

## Elevation policies

A method's `ElevationPolicy` selects the elevation fed into the horizon-dip term:

- `OFFICIAL_CITY_TABLE` — use elevation 0 (matches the published city table).
- `OBSERVER_ELEVATION` — use the actual observer/coordinate elevation.

The Morocco method defaults to `OFFICIAL_CITY_TABLE`.

## Testing strategy

- **Unit tests** for every pure component (Julian date, solar position, hour
  angles, time conversion, method parameters, models, validation).
- **Fixture-based** Habous parser tests; the provider is tested with an injected
  fetcher — normal tests never hit the network.
- **Regression suite** against cached official July 2026 values (5-prayer
  tolerance of 2 min; a written report of signed diffs, MAE and max AE).
- **Annual coverage** — every day of 2026 across 8 cities checks ordering,
  normalization, plausible day-to-day movement, and offset provenance.
- **Clock-transition tests** confirm a ~1-hour civil shift with a continuous UTC
  solar instant (proving the offset is applied once).
- **Property-style tests** over randomized Moroccan inputs.
- **Live tests** are tagged `live` and excluded from CI; run via `./gradlew
liveTest`.
- CI enforces JaCoCo thresholds (calculation ≥90%, time ≥95%, Habous parser
  ≥90%, total ≥80%) and Spotless style.
