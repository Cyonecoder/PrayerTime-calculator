# Habous exact compatibility

How the application guarantees output that exactly matches the official Habous
timetable, and how it behaves when official data is not available.

## Source modes

| Mode | Behaviour | Source of truth |
|------|-----------|-----------------|
| `CALCULATED` | Reconstructed astronomical method. No official override. | formula |
| `OFFICIAL` | Exact validated Habous value, or `503 HABOUS_OFFICIAL_DATA_UNAVAILABLE`. Never silently calculated. | official snapshot |
| `AUTO` | Official when available; otherwise a **flagged** calculated fallback. | official → formula |
| `COMPARE` | Both official and reconstructed results plus per-prayer differences. | both |

## Providers

- `AstronomicalPrayerTimesProvider` — serves `CALCULATED` (and the `AUTO` fallback).
- `HabousOfficialPrayerTimesProvider` — serves the exact official value or fails closed.
- `HabousCompatiblePrayerTimesProvider` — the top-level provider that dispatches on mode.

Official values are served **verbatim**: never rounded, corrected, or recomputed.

## Result metadata

Every result carries provenance:

```json
{
  "cityId": "casablanca",
  "date": "2026-07-07",
  "sourceMode": "AUTO",
  "source": "HABOUS_OFFICIAL",
  "official": true,
  "fallbackUsed": false,
  "habousCityId": 58,
  "sourceChecksum": "7ab1d6...",
  "fetchedAt": "2026-07-07T18:08:48Z",
  "parameterVersion": null,
  "times": { "fajr": "04:38", "sunrise": "06:23", "dhuhr": "13:41",
             "asr": "17:20", "maghrib": "20:49", "isha": "22:19" }
}
```

A calculated fallback instead reports `source: ASTRONOMICAL_RECONSTRUCTED`,
`official: false`, `fallbackUsed: true`, `parameterVersion: "morocco-habous-v1"`.
`fetchedAt` lets callers detect stale official data.

## Fallback behaviour

`AUTO` priority: (1) validated official value → (2) reconstructed calculation,
always with `fallbackUsed: true`. `OFFICIAL` **never** falls back — it raises
`HabousDataUnavailableException` (`503`). The application always produces a
result offline: with no network and no official snapshot, `CALCULATED`/`AUTO`
still calculate.

## API

```
GET /api/v1/cities/{cityId}/prayer-times?date=YYYY-MM-DD&source=auto|official|calculated|compare
GET /api/v1/cities/{cityId}/prayer-times/comparison?date=YYYY-MM-DD
```

- `200` served result · `400` unknown city / bad date / bad source ·
  `503 HABOUS_OFFICIAL_DATA_UNAVAILABLE` (OFFICIAL only).
- CLI equivalents: `prayer-times --city <c> --date <d> [--source <mode>]` and
  `serve [--port <n>]` (JDK built-in HTTP server, no runtime dependency).

## Bundled official data

Validated snapshots live under `src/main/resources/official/habous/` with an
`index.json` manifest. Each snapshot records `sourceChecksum`, `parserVersion`,
and `fetchedAt`. On load, `OfficialHabousStore` validates every snapshot
(`OfficialSnapshotValidator`: expected city, mapping, contiguous dates, no
duplicates, coherent times, provenance present) and **rejects** any that fail —
they are never served.

## CI enforcement

`./gradlew habousCompatibilityTest` is the permanent gate (workflow
`.github/workflows/habous-compatibility.yml`). It requires:

```
Official-mode exact-match percentage: 100%
Unresolved official mismatches: 0
Mapped city coverage: complete
Formula: within its accepted band
```

Any official-mode mismatch, missing mapping, or altered/incomplete snapshot fails
the build. Reports are written to `build/reports/habous-compatibility/`.
