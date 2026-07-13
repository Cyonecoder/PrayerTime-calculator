# Sec-approach calculation

The **sec-approach** is a local, deterministic, offline, formula-based
calculation that reproduces the official Moroccan Habous timetable to ~97%
exact-minute agreement, without ever reading or memorising the timetable at
runtime.

## Model

```
publishedMinute = floor((rawSeconds + offsetSeconds[city][prayer]) / 60)
```

Operation order (exactly this, once each):

```
city/date
  → reconstructed raw astronomical seconds   (frozen global method, morocco-habous-reconstructed.json)
  → + one constant city/prayer offset        (morocco-habous-secaproach.json)
  → floor to the minute
  → HH:mm
```

The only per-city parameters are **six integer-second constants** — fajr,
sunrise, dhuhr, asr, maghrib, isha. There is no per-date, per-month, or
per-season term, no memorised daily table, no interpolation from official
values, and no nearest-city / province / Casablanca fallback. A city without
offsets is unsupported and fails closed (`SecondsApproachPrayerTimesProvider`
never guesses).

## What is (and isn't) allowed to change

- The six-constant model and the operation order are **fixed**.
- The 14 original city offset sets (agadir, casablanca, dakhla, errachidia, fez,
  kenitra, laayoune, marrakech, meknes, ouarzazate, oujda, rabat, tangier,
  tetouan) are **locked** — their committed values are held byte-for-byte in
  `morocco-habous-secaproach-base14.json` and guarded by
  `SecondsApproachCoverageTest`.
- The frozen global reconstructed method is never changed per city.

## Every city is independently verified against the live source

Before a city may be trained, it is re-verified against the **live** Habous
source by `research verify-habous-cities`
(`HabousCityVerifier`, method `live-select-and-timetable`):

1. the official `<select name="ville">` dropdown is fetched and every option
   parsed with provenance (id, exact + normalized Arabic, checksum) into
   `build/reports/secapproach-all-cities/habous-live-city-options.json`;
2. the candidate is matched to a **unique** option by exact conservatively-
   normalized Arabic name or a known official-spelling alias — never a fuzzy
   match, never a copied or invented id;
3. its timetable is fetched, must contain the expected Arabic name, parse to a
   coherent contiguous six-column table, and be reproduced by a second
   independent fetch.

Each of the 67 candidates receives exactly one status (`VERIFIED`,
`NOT_LISTED_BY_HABOUS`, `AMBIGUOUS_NAME`, `TIMETABLE_UNAVAILABLE`,
`INVALID_TIMETABLE`, `MANUAL_REVIEW_REQUIRED`) in
`reports/secapproach-all-cities/habous-city-verification.json`. Only `VERIFIED`
cities are written to `habous-city-mapping.json` (with `verifiedAt` /
`sourceChecksum` provenance) and only verified cities are ever trained. Seven
candidates are `NOT_LISTED_BY_HABOUS` (no dedicated page) and fail closed.

## Habous is a research/testing oracle only

Official Habous data is used solely to **train and verify** the constants
offline. It is never copied into production output. The reference timetables for
every verified city live off the classpath under `research-data/habous` (one
snapshot per city, the validated 30-day window 2026-06-17…2026-07-16 with full
provenance), each fetched with `research fetch-habous-live`, which requires the
mapped Arabic name to appear on the fetched page before accepting any data.

## Training and verification

`SecondsApproachOffsetFitter` fits each city/prayer constant by maximising
exact-minute agreement against the oracle, using the *identical* arithmetic as
the production provider, and choosing deterministically (centre of the widest
tie plateau — the offset furthest from a rounding boundary). A city is committed
only if it reaches the **95% exact-minute** verification band; otherwise it is
reported and excluded.

```
research fit-secaproach --dataset <dir> [--cities a,b] [--min-exact 0.95] \
  [--base <locked-file>] [--output <file>]
```

`--base` preserves already-shipped cities verbatim so onboarding new cities never
changes an existing city's output.

## Coverage

From the authoritative 67-city candidate inventory
(`research/habous-candidate-inventory.json`): 14 locked + **42 newly verified** =
**56 committed** cities. Four mapped cities fell below the band (berrechid,
fkih-ben-salah, larache, sidi-kacem) and seven have no dedicated Habous page
(bouskoura, kabila, sale, sidi-rahal, tamansourt, tamesna, temara); all eleven
are excluded and fail closed. See `reports/sec-approach-expansion.md` for the
per-city accuracy table.
