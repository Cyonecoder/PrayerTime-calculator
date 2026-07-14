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

The deterministic trainer lives in the `audit.secapproach` package
(`SecApproachTrainer` and friends). For each (city, prayer) it pairs the raw
reconstructed astronomical **seconds** (from `HabousRawEngine` over
`ReconstructedMethod.frozen()`, not the already-rounded minute) with the official
minute, then runs an exhaustive integer-second search over **[-900, +900]** and
keeps the winner by a fixed lexicographic tie-break: highest exact-minute count,
then within-±1 count, then lowest MAE, then lowest max error, then lowest
absolute offset, then lowest signed offset. All ranking quantities are integers,
so the same dataset reproduces the same offsets and report byte-for-byte — no
randomness, floating-point tie-break, ML, or per-date fitting. A city is
committed only if it reaches the **95% exact-minute** band; below-band cities are
reported and excluded, and any offset landing on the ±900 boundary is flagged
(coordinates/timezone/mapping are rechecked before the window is ever widened).

```
research train-secaproach --dataset <dir> [--base <locked-file>] \
  [--min-exact 0.95] [--low -900] [--high 900] \
  [--offsets-out <file>] [--report-out <file>]
```

`--base` preserves already-shipped cities verbatim so onboarding new cities never
changes an existing city's output.

## Parameter resource & versioning

The frozen, reviewed parameters live at
`src/main/resources/research/morocco-habous-secaproach.json` (unchanged path).
It is **`morocco-habous-secaproach-v2`** — the 14 original offset objects
preserved exactly plus the accepted new-city objects, all sorted by canonical
slug — and carries provenance:

```json
{
  "parameterVersion": "morocco-habous-secaproach-v2",
  "model": "publishedMinute = floor((rawSeconds + offsetSeconds[city][prayer]) / 60)",
  "generatorVersion": "secapproach-trainer-v1",
  "generatedFrom": { "firstDate": "2026-06-17", "lastDate": "2026-07-16",
                     "datasetChecksum": "…", "verifiedCityCount": 60, "trainedCityCount": 56 }
}
```

The production provider (`SecondsApproachPrayerTimesProvider`) loads **only** this
frozen resource and **rejects any file whose `parameterVersion` differs from its
own `PARAMETER_VERSION`** — so it can never serve unreviewed parameters. Review
flow: `research train-secaproach` writes a candidate to
`build/reports/secapproach-all-cities/generated-parameters.json` (never the
resource); a human reviews it and commits the deterministic copy to the resource
path. Tests read/compare the committed resource but **never rewrite it**.

## Honest accuracy — not an annual claim

The reference data is a single **30-day summer window** (2026-06-17…2026-07-16),
so a full-dataset percentage is **in-sample** and must not be read as year-round
accuracy. For every newly trained city the trainer also reports an
overfitting-aware, out-of-sample estimate (`holdout-report.json`), splitting each
city's dates chronologically:

- Training: 2026-06-17…2026-07-06 (20 days) — the offset is fit here only
- Validation: 2026-07-07…2026-07-11 (5 days) — used only to break ties
- Holdout: 2026-07-12…2026-07-16 (5 days) — the selected offset is scored here **untouched**

(For any other window length the split is proportional: first two-thirds / next
one-sixth / final one-sixth.)

Across the 42 newly trained cities the **out-of-sample holdout mean is ≈89%
exact**, versus ≈97% in-sample — the gap is the honest cost of a short summer
window. The shipped offset is the "final release offset" refit on all validated
dates (so production is unchanged); the pre-refit holdout metrics are preserved
in the report and the full-dataset numbers are labelled in-sample. No
annual/year-round accuracy is claimed from this dataset.

## Per-city training catalog

`research train-new-cities` trains **every** candidate without current offsets
(the 53 new cities), independently and per prayer, and writes a catalog
(`reports/secapproach-all-cities/new-city-training.json`) giving each city exactly
one status:

| Status | Meaning | Offsets |
|---|---|---|
| `ACCEPTED` | verified, 180 obs, ≥95% in-sample, offsets sane | committed |
| `FAILED_ACCURACY_GATE` | verified + data, but below the band | none |
| `MAPPING_UNVERIFIED` | no verified Habous mapping / page | none |
| `INSUFFICIENT_DATA` | verified but not a full 180-observation window | none |
| `SUSPICIOUS_OFFSET` | offset hit the search boundary or exceeded a many-minute threshold (wrong-city/coordinate signature) | none |
| `MANUAL_REVIEW_REQUIRED` | verification was ambiguous / needs a human | none |

A non-`ACCEPTED` city **never** receives offsets, so the provider keeps failing
closed for it. Each record carries the dataset provenance (window,
`observationCount`, `sourceChecksums`) and the training/validation/holdout
(out-of-sample) and full-dataset (in-sample) metrics.

## Coverage

From the authoritative 67-city candidate inventory
(`research/habous-candidate-inventory.json`): 14 locked + **42 ACCEPTED** =
**56 committed** cities. Of the 53 new cities: **42 ACCEPTED**, **4
FAILED_ACCURACY_GATE** (berrechid, fkih-ben-salah, larache, sidi-kacem), **7
MAPPING_UNVERIFIED** (bouskoura, kabila, sale, sidi-rahal, tamansourt, tamesna,
temara). All non-accepted cities fail closed. See
`reports/sec-approach-expansion.md` for the per-city accuracy table.
