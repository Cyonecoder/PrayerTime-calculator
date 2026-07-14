# Salat / Prayer-Time Calculator — Command Reference

Every command you can run, by category, with purpose and examples.

## How to run

All commands go through the Gradle `run` task:

```bash
./gradlew run --args="<command> <flags>"
```

Add `-q` to silence Gradle's own build chatter and see only the command output:

```bash
./gradlew -q run --args="calculate --city rabat --date 2026-07-07"
```

There are three top-level verbs:

| Verb | Purpose | Network |
|------|---------|---------|
| *(none)* | **Production calculator** — prayer times for a city/date | Offline |
| `research` | **Research/audit tooling** — verify, collect, train, evaluate | Offline, **except** the explicitly live subcommands |
| `audit` | **Release validation** of the reconstructed method | Offline |

> The production calculator is offline for every command **except the standalone
> `compare`**, which fetches the official schedule from Habous live. (`calculate
> --source official/auto/compare` instead uses the *bundled* official snapshots
> and stays offline.) In the research tooling, only `research fetch-habous*` and
> `research verify-habous-cities` contact Habous.

Dates are `YYYY-MM-DD`. The bundled research dataset covers **2026-06-17 …
2026-07-16**. Cities are slugs or any alias (Latin/Arabic, case-insensitive) —
list them with `cities`.

---

## 1. Production prayer times

The everyday commands. Default source is `calculated` (the reconstructed Morocco
Habous method); `secaproach` is the city-corrected variant (~97% exact).

### `calculate` — one city, one day

```bash
./gradlew -q run --args="calculate --city casablanca --date 2026-07-07"
./gradlew -q run --args="calculate --city casablanca --date 2026-07-07 --seconds"
./gradlew -q run --args="calculate --city casablanca --date 2026-07-07 --source official"
```

- **Purpose:** the six prayer times for a city on a date.
- `--source calculated|official|auto|compare` (default `calculated`):
  - `calculated` — reconstructed astronomical method (offline, always available).
  - `official` — exact bundled Habous value, or fails closed (`503`) if none.
  - `auto` — official if available, else a flagged calculated fallback.
  - `compare` — both, with per-prayer differences.
- `--seconds` — show `HH:mm:ss` instead of `HH:mm`.

### `range` — one city, a date range

```bash
./gradlew -q run --args="range --city fez --from 2026-07-01 --to 2026-07-07"
./gradlew -q run --args="range --city fez --from 2026-07-01 --to 2026-07-07 --source compare"
```

- **Purpose:** a table of daily times across a date span. Same `--source` / `--seconds`.

### `month` — one city, a calendar month

```bash
./gradlew -q run --args="month --city marrakech --year 2026 --month 7"
./gradlew -q run --args="month --city marrakech --year 2026 --month 7 --seconds"
```

- **Purpose:** a full month table. Same `--source` / `--seconds`.

### `secaproach` — city-corrected sec-approach (recommended)

```bash
# single day
./gradlew -q run --args="secaproach --city safi --date 2026-07-07"
# a range, second-precise, with the vs-official column
./gradlew -q run --args="secaproach --city safi --from 2026-06-17 --to 2026-07-16 --seconds"
# a whole month
./gradlew -q run --args="secaproach --city nador --month 7 --year 2026"
# per-prayer official − sec-approach difference table
./gradlew -q run --args="secaproach --city rabat --from 2026-06-17 --to 2026-07-16 --compare"
```

- **Purpose:** the per-city, per-prayer offset-corrected times
  (`publishedMinute = floor((rawSeconds + offset) / 60)`), the most accurate
  offline result.
- Choose exactly one of `--date <d>` | `--month <m> [--year <y>]` | `--from <d> --to <d>`.
- `--seconds` shows the raw second-precise time; `--compare` shows the
  official − sec-approach differences.
- The `vs official` column uses the bundled official snapshot for the 14 original
  cities, and the off-classpath research oracle for the others (only for
  2026-06-17…2026-07-16; `-` outside that window).
- **Only the 48 committed cities are supported.** Any other city (gate-failed or
  unmapped) fails closed: `Error: no sec-approach offsets for city '<x>'`.

### `prayer-times` — JSON output

```bash
./gradlew -q run --args="prayer-times --city agadir --date 2026-07-07 --source auto"
```

- **Purpose:** machine-readable JSON with provenance (source, checksum,
  `fetchedAt`, parameter version). Same `--source` values.

### `compare` — calculated vs official for a day  🌐 network

```bash
./gradlew -q run --args="compare --city oujda --date 2026-07-07"
```

- **Purpose:** side-by-side reconstructed vs live official with per-prayer deltas.
- **This is the only production command that contacts Habous** (live fetch); with
  no network it reports official data unavailable. For an offline comparison
  against the bundled snapshots use `calculate --source compare`.

### `clock` — Morocco civil-time info

```bash
./gradlew -q run --args="clock --date 2026-07-07"
```

- **Purpose:** the civil-time/offset context for a date (DST/Ramadan clock).

### `cities` — list supported cities

```bash
./gradlew -q run --args="cities"
```

- **Purpose:** every registered city slug and its aliases.

### `serve` — read-only HTTP API

```bash
./gradlew -q run --args="serve --port 8080"
```

- **Purpose:** starts the built-in JDK HTTP server (no runtime dependency):
  `GET /api/v1/cities/{cityId}/prayer-times?date=YYYY-MM-DD&source=auto`.
  `--port 0` picks a free port.

---

## 2. Research — verify cities against live Habous  🌐 network

### `research verify-habous-cities`

```bash
# re-verify all 67 candidates and write the audit + report into a directory
./gradlew run --args="research verify-habous-cities \
  --inventory all-67 \
  --output build/reports/secapproach-all-cities"

# re-verify only a subset (non-destructive: other mappings are preserved)
./gradlew run --args="research verify-habous-cities \
  --inventory casablanca,rabat,safi \
  --output build/reports/secapproach-all-cities --delay-seconds 2"
```

- **Purpose:** independently re-verify each candidate against the live Habous
  `<select name="ville">` dropdown + its timetable, assign one status
  (`VERIFIED` / `NOT_LISTED_BY_HABOUS` / `AMBIGUOUS_NAME` / `TIMETABLE_UNAVAILABLE`
  / `INVALID_TIMETABLE` / `MANUAL_REVIEW_REQUIRED`), and rewrite
  `habous-city-mapping.json` with the verified-only mappings + provenance.
- Flags: `--inventory <selector|list>` (default `all-67`), `--output <dir>`,
  `--delay-seconds N`, `--dropdown-ville <id>`, `--mapping <file>`,
  `--options-out <file>`, `--report-out <file>`.
- Writes `habous-live-city-options.json` (parsed dropdown) and
  `habous-city-verification.json` (per-candidate statuses).

---

## 3. Research — collect official reference data  🌐 network

### `research fetch-habous-live` (current live month)

```bash
./gradlew run --args="research fetch-habous-live \
  --cities all-mapped \
  --output research-data/habous \
  --delay-seconds 2"
```

- **Purpose:** fetch the current live Habous month for the selected cities into
  dated snapshot files with full provenance (URL, `fetchedAt`, SHA-256, parser
  version). Requires the mapped Arabic name to appear on the page before
  accepting data; a city that can't be verified is reported, not filled in.
- `--cities <selector|list>` (see selectors below), `--output <dir>`,
  `--delay-seconds N`.

### `research fetch-habous` (specific Gregorian months)

```bash
./gradlew run --args="research fetch-habous \
  --cities all-mapped --year 2026 --months 6,7 \
  --output research-data/habous --delay-seconds 2 --retries 3"
```

- **Purpose:** collect specific historical Gregorian months.
- `--year <y>`, `--months <1-12|1,2,3>`, plus `--retries N`, `--refresh`,
  `--report <dir>`.

### `research dataset-coverage` (offline)

```bash
./gradlew -q run --args="research dataset-coverage \
  --dataset research-data/habous --year 2026 --months 6,7"
```

- **Purpose:** report which cities/months are present in a dataset directory. No
  network.

---

## 4. Research — train the sec-approach  (offline)

### `research train-secapproach` — full trainer

```bash
# candidate run over the whole dataset, preserving the 14 locked cities
./gradlew -q run --args="research train-secapproach \
  --dataset research-data/habous \
  --base src/main/resources/research/morocco-habous-secaproach-base14.json \
  --output build/reports/secapproach-all-cities"

# train only the new verified cities, restricted to the validated window
./gradlew -q run --args="research train-secapproach \
  --dataset research-data/habous \
  --cities all-new-verified \
  --from 2026-06-17 --to 2026-07-16 \
  --output build/reports/secapproach-all-cities"
```

- **Purpose:** deterministically fit each city/prayer offset (exhaustive integer
  search in `[-900, +900]`, lexicographic tie-break), gate on the per-city
  accuracy gates (full ≥90%, out-of-sample **holdout ≥85%**, within-1min ≥98%,
  max ≤2min), and write:
  - `generated-parameters.json` — the **candidate** parameter file (review, then
    copy to `src/main/resources/research/morocco-habous-secaproach.json`),
  - `training-report.json` — per-city/per-prayer metrics + histograms,
  - `holdout-report.json` — the honest out-of-sample estimate.
- Flags: `--cities <selector|list>`, `--from <d>`, `--to <d>`, `--base <file>`
  (preserve those cities verbatim), `--low N` / `--high N` (search window),
  `--output <dir>` (or the granular `--offsets-out` / `--report-out` /
  `--holdout-out`).
- **Never writes the committed production resource** — only candidates under
  `build/…`. Reproducible byte-for-byte.

### `research train-new-cities` — per-city status catalog

```bash
./gradlew -q run --args="research train-new-cities \
  --dataset research-data/habous \
  --output reports/secapproach-all-cities/new-city-training.json"
```

- **Purpose:** train **every** candidate without current offsets (53 cities) and
  emit a catalog giving each one status (`ACCEPTED` / `FAILED_ACCURACY_GATE` /
  `MAPPING_UNVERIFIED` / `INSUFFICIENT_DATA` / `SUSPICIOUS_OFFSET` /
  `MANUAL_REVIEW_REQUIRED`) with dataset provenance and metrics. Only `ACCEPTED`
  cities carry offsets; the rest fail closed.
- Flags: `--low N` / `--high N`, `--suspicious-seconds N`, `--output <file>`.

---

## 5. Research — evaluate parameters  (offline)

### `research evaluate-secapproach`

```bash
# score the committed parameters against the dataset vs the global baseline
./gradlew -q run --args="research evaluate-secapproach \
  --dataset research-data/habous \
  --parameters src/main/resources/research/morocco-habous-secaproach.json \
  --baseline global \
  --output build/reports/secapproach-all-cities"

# evaluate a freshly generated candidate for a few cities
./gradlew -q run --args="research evaluate-secapproach \
  --dataset research-data/habous \
  --parameters build/reports/secapproach-all-cities/generated-parameters.json \
  --cities casablanca,rabat,safi --baseline global"
```

- **Purpose:** score an existing parameter file (no retraining): per-city and
  aggregate exact / within-1min / max-error, the improvement over the **global**
  reconstructed baseline, and whether the aggregate release gates pass. Writes
  `evaluation-report.json`.
- Flags: `--parameters <file>` (required), `--baseline global|none`,
  `--cities <selector|list>`, `--from <d>`, `--to <d>`, `--output <dir>`.

---

## 5b. Research — generate the report bundle  (offline)

### `research reports`

```bash
./gradlew -q run --args="research reports \
  --dataset research-data/habous \
  --output build/reports/secapproach-all-cities"
```

- **Purpose:** generate the full, deterministic sec-approach report bundle into
  the output directory:
  - `summary.md` — inventory, dataset, aggregate metrics, parameter-change pointers;
  - `city-verification.md` / `.json` — per-candidate live-verification status;
  - `dataset-coverage.md` / `.csv` — per-city window/days/observations/checksum;
  - `training-metrics-by-city.csv`, `training-metrics-by-prayer.csv` — sec metrics;
  - `holdout-metrics-by-city.csv` — training/validation/holdout/in-sample exact;
  - `baseline-vs-secapproach.csv` — per-city global vs sec vs improvement;
  - `rejected-cities.md` — gate-failed + not-listed cities with reasons;
  - `existing-city-regression.md` — the 14 offsets before/after + unchanged proof;
  - `generated-parameters.json` — the candidate parameter file.
- Flags: `--dataset <dir>`, `--base <file>` (locked reference), `--output <dir>`.
- Offline and reproducible byte-for-byte.

---

## 6. Research — parameter search & global model  (offline)

For reconstructing/searching the underlying global method (advanced).

```bash
# search one prayer's parameter grid
./gradlew -q run --args="research search-parameter \
  --prayer maghrib --dataset research-data/habous"

# search rounding policy / operation order
./gradlew -q run --args="research search-rounding --dataset research-data/habous"

# re-rank previously written candidates
./gradlew -q run --args="research rank-candidates --input build/reports/habous-search --top 20"

# infer the global reconstructed model
./gradlew -q run --args="research infer-global-model --dataset research-data/habous"
```

- **`search-parameter`** — grid-search one prayer's astronomical parameter.
  `--prayer <fajr|sunrise/chourouk|dhuhr|asr|maghrib|isha>`, plus
  `--cities a,b`, `--from`, `--to`, `--output <dir>`.
- **`search-rounding`** — search rounding policy + operation order.
- **`rank-candidates`** — re-rank a search's `all-candidates.csv`. `--input <dir>`,
  `--top N`.
- **`infer-global-model`** — fit the frozen global model family. `--output <dir>`.

---

## 7. Audit — release validation  (offline)

```bash
# validate the reconstructed method and print the GO / NO-GO release decision
./gradlew -q run --args="audit validate-habous-method \
  --method MOROCCO_HABOUS_RECONSTRUCTED \
  --dataset research-data/habous --cities all"

# show the reconstructed schedule for one city/day (or a whole dataset)
./gradlew -q run --args="audit reconstruct-times --city rabat --date 2026-07-07"
./gradlew -q run --args="audit reconstruct-times --city rabat --dataset research-data/habous"
```

- **`validate-habous-method`** — evaluate the frozen reconstructed method against
  the dataset, apply the compatibility gates, and report the release decision.
  Flags: `--method MOROCCO_HABOUS_RECONSTRUCTED`, `--dataset <dir>`,
  `--cities all|a,b`, `--include-approved-corrections`,
  `--include-official-overrides`, `--output <dir>`.
- **`reconstruct-times`** — the raw reconstructed schedule for inspection.
  `--city <slug>` and either `--date <d>` or `--dataset <dir>`.

---

## City selectors

Anywhere a `--cities` / `--inventory` value is accepted you can use a named
selector or an explicit comma list.

| Selector | Cities | Count |
|----------|--------|------:|
| `all-candidates` / `all-67` | the whole candidate inventory | 67 |
| `all-mapped` | cities with a verified Habous mapping | 60 |
| `all-new-verified` | verified new cities (not the original 14) | 46 |
| `all-sec-supported` | cities with committed sec-approach offsets | 48 |
| `existing-sec` | the 14 original locked cities | 14 |
| `a,b,c` | explicit slug/alias list | — |

```bash
--cities al-hoceima,assilah,azrou
--cities all-new-verified
```

---

## Build, test & release checks

```bash
./gradlew test        # unit + integration tests (offline)
./gradlew check       # test + coverage verification + spotless format check (offline)
./gradlew liveTest    # ONLY the @Tag("live") network tests (requires internet)
./gradlew build       # compile, test, assemble the jar
```

- `test` / `check` never hit the network — live tests are excluded by tag.
- `liveTest` runs the opt-in tests that contact Habous; add
  `-DhabousLiveAll=true` to recheck every mapping instead of a sample.

---

## Typical end-to-end workflow

```bash
# 1. Re-verify every candidate against live Habous (updates the mapping)
./gradlew run --args="research verify-habous-cities --inventory all-67 \
  --output build/reports/secapproach-all-cities"

# 2. Collect the official reference data for the verified cities
./gradlew run --args="research fetch-habous-live --cities all-mapped \
  --output research-data/habous --delay-seconds 2"

# 3. Train the offsets (candidate output under build/…), preserving the 14 locked
./gradlew -q run --args="research train-secapproach --dataset research-data/habous \
  --base src/main/resources/research/morocco-habous-secaproach-base14.json \
  --output build/reports/secapproach-all-cities"

# 4. Evaluate the candidate against the global baseline
./gradlew -q run --args="research evaluate-secapproach --dataset research-data/habous \
  --parameters build/reports/secapproach-all-cities/generated-parameters.json --baseline global"

# 5. Review, then copy the candidate to the reviewed resource and re-run the tests
cp build/reports/secapproach-all-cities/generated-parameters.json \
   src/main/resources/research/morocco-habous-secaproach.json
./gradlew check

# 6. Serve or query the result
./gradlew -q run --args="secaproach --city safi --date 2026-07-07"
```
