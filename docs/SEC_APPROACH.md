# Sec-approach calculation

The **sec-approach** is a local, deterministic, offline, formula-based
calculation that reproduces the official Moroccan Habous timetable with **97.09%
exact-minute agreement on the validated 2026-06-17 through 2026-07-16 reference
window** (within ±1 minute for every observation on that window), without ever
reading or memorising the timetable at runtime.

> Those figures are measured on a single **30-day summer window** and are stated
> as exact-minute agreement on that reference window only. This is **not** a
> claim of annual or year-round accuracy, and nothing here is described as
> "100% accurate" — see [Honest accuracy](#honest-accuracy--not-an-annual-claim).

## The 67-city candidate inventory

Coverage is driven by an authoritative **67-city candidate inventory**
(`src/main/resources/research/habous-candidate-inventory.json`): each candidate
records its canonical slug, original source spelling, Arabic name, original HTML
state (48 active / 19 commented), and sec-approach state (14 already calibrated /
53 requiring training). The inventory is the single source of truth for which
cities are considered; a city being *in the inventory* means nothing about
whether it is supported (see below).

## Registry support vs sec-approach support

These are two different, deliberately separate notions:

- **Registry support** — the city exists in the production city model
  (`cities/morocco-cities.json`) with coordinates, zone, and aliases. All 67
  candidates are registry cities. Registry support only means the calculator can
  *resolve the name and compute an astronomical schedule* for it.
- **Sec-approach support** — the city is served by the city-corrected
  sec-approach. A city is sec-supported **only** when it has all of: a verified
  Habous mapping, a complete validated 180-observation dataset, accepted
  six-prayer offsets, and passing tests. **48** of the 67 qualify.

Having coordinates (registry support) is **never** sufficient for sec-approach
support. A registry city without accepted offsets is not sec-supported and fails
closed (see [Unsupported-city behavior](#unsupported-city-behavior)).

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

Across the 34 accepted new cities the **out-of-sample holdout mean is ≈90.9%
exact-minute agreement on the validated window**, versus ≈97% in-sample — the gap
is the honest cost of a short summer window, and it is exactly why the per-city
gate requires **holdout ≥ 85%** rather than trusting the in-sample number. The
shipped offset is the "final release offset" refit on all validated dates (so
production is unchanged); the pre-refit holdout metrics are preserved in the
report and the full-dataset numbers are labelled in-sample. **No annual or
year-round accuracy is claimed from this 30-day summer dataset**, and no result
is described as "100% accurate" — every figure is exact-minute agreement on the
2026-06-17 through 2026-07-16 reference window.

## Release accuracy gates

`SecApproachGates` defines the release thresholds, enforced by
`SecApproachReleaseGateTest`:

**Aggregate** (all supported cities) — actual on the committed set in parentheses:
exact ≥ 95% (**97.1%**), improvement over the global formula ≥ 10 pp
(**+17.9 pp**), within ±1 min ≥ 99.5% (**100%**), max abs error ≤ 2 min
(**1 min**).

**Per newly trained city** — full-dataset exact ≥ 90%, out-of-sample **holdout
exact ≥ 85%**, within ±1 min ≥ 98%, max abs error ≤ 2 min. A city below any
threshold is investigated (coordinates, mapping, source data, prayer by prayer)
and, if unresolved, marked `FAILED_ACCURACY_GATE` — never silently shipped. The
trainer admits a new city only if it passes every per-city gate, so the committed
offsets are exactly the gated set.

**Existing 14 cities (regression)** — offsets and predictions unchanged (locked,
byte-identical to `…-base14.json`), aggregate accuracy not decreased, no city
becomes unsupported, aliases keep resolving, global calculation unchanged.

## Per-city training catalog

`research train-new-cities` trains **every** candidate without current offsets
(the 53 new cities), independently and per prayer, and writes a catalog
(`reports/secapproach-all-cities/new-city-training.json`) giving each city exactly
one status:

| Status | Meaning | Offsets |
|---|---|---|
| `ACCEPTED` | verified, 180 obs, passes all per-city gates | committed |
| `FAILED_ACCURACY_GATE` | verified + data, but fails a per-city gate | none |
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
(`research/habous-candidate-inventory.json`): 14 locked + **34 ACCEPTED** =
**48 committed** cities. Of the 53 new cities: **34 ACCEPTED**, **12
FAILED_ACCURACY_GATE** (all on the out-of-sample holdout < 85%: berkane,
chefchaouen, el-jadida, fkih-ben-salah, khenifra, khouribga, ksar-el-kebir,
larache, midelt, sidi-ifni, sidi-kacem, taroudant), **7 MAPPING_UNVERIFIED**
(bouskoura, kabila, sale, sidi-rahal, tamansourt, tamesna, temara). All
non-accepted cities fail closed.

## Accuracy by city and prayer

Aggregate exact-minute agreement on the validated 2026-06-17 through 2026-07-16
reference window is **97.09%** across the 48 sec-supported cities (100% within
±1 minute, max error 1 minute, +17.94 pp over the global reconstructed formula).
Per-city and per-prayer figures are not summarised as a single number here —
they are produced deterministically by `research reports` and live in
`build/reports/secapproach-all-cities/`:

- `training-metrics-by-city.csv` — exact / within-±1 / MAE / max, and out-of-sample
  holdout, for every city;
- `training-metrics-by-prayer.csv` — offset and metrics for every city × prayer;
- `baseline-vs-secapproach.csv` — global vs sec-approach per city;
- `holdout-metrics-by-city.csv` — training / validation / holdout / in-sample;
- `summary.md` — the aggregate roll-up.

Every figure is exact-minute agreement **on that validated reference window**;
none is presented as year-round or "100% accurate".

## Unsupported-city behavior

A city that is not sec-supported (gate-failed, mapping-unverified, or simply not
in the 48) receives **no offsets** and the provider **fails closed** — it never
falls back to a nearby city, a province, Casablanca, or a silent zero offset:

```
$ ./gradlew -q run --args="secaproach --city larache --date 2026-07-07"
Error: no sec-approach offsets for city 'larache'
```

The city can still be resolved by the registry and computed with the global
`calculate` method (registry support); it is only the city-corrected sec-approach
that fails closed. This is deliberate: a wrong-but-plausible answer is worse than
an explicit "unsupported".

See `reports/sec-approach-expansion.md` and the generated
`rejected-cities.md` for the per-city status table.
