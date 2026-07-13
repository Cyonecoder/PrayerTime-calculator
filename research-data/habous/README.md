# Habous research dataset

Official Habous timetables for **every verified candidate city** (see
`reports/secapproach-all-cities/habous-city-verification.json`). This is
*research/audit* input only — it is **not** a production resource and is **not**
on the classpath.

## Contents

One `<slug>-live-<firstDate>.json` snapshot per verified city, each covering the
validated 30-day window **2026-06-17 … 2026-07-16** (30 dates × 6 daily values =
180 observations per city). This is the same window as the original sec-approach
training data, for comparability.

## Provenance

Each snapshot was produced by `research fetch-habous-live`
(`LiveHabousCollector`), which fetches the city's `ville` page, **requires the
mapped Arabic name to appear on the page**, parses the nine-column Hijri table,
and resolves each row to an absolute Gregorian date. No value is calculated,
interpolated, or invented — a city that cannot be fetched, verified and parsed is
reported, not filled in. Each file records:

`source`, `city` (registry slug), `habousVilleId`, `habousArabicName`,
`sourceUrl`, `fetchedAt`, `sourceSha256`, `parserVersion`, and the complete
`days` records (`date`, `fajr`, `sunrise`, `dhuhr`, `asr`, `maghrib`, `isha`).

`HabousResearchDatasetTest` enforces the 30-day window, six-value completeness,
contiguous dates, and provenance offline; only the explicitly tagged
`research fetch-habous-live` / `research verify-habous-cities` commands contact
Habous.

## Schema

```json
{
  "source": "habous-live",
  "city": "casablanca",
  "habousVilleId": "58",
  "habousArabicName": "الدار البيضاء",
  "sourceUrl": "https://www.habous.gov.ma/prieres/index.php?ville=58",
  "fetchedAt": "2026-…Z",
  "sourceSha256": "…",
  "parserVersion": "habous-live-1",
  "days": [
    { "city": "casablanca", "date": "2026-06-17",
      "fajr": "04:35", "sunrise": "06:23", "dhuhr": "13:40",
      "asr": "17:19", "maghrib": "20:49", "isha": "22:20" }
  ]
}
```
