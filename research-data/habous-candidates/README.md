# Habous candidate-city research dataset

Official Habous timetables for the **candidate cities** added from the
authoritative 67-city inventory (see
`src/main/resources/research/habous-candidate-inventory.json`). Like
`../habous`, this is *research/audit* input only — it is **not** a production
resource and is **not** on the classpath.

It is kept separate from `../habous` so the frozen global reconstructed method
and its release gate (which are calibrated on the original 14 cities) are never
perturbed by the candidate data. The sec-approach fitter and coverage tests read
both directories.

## Provenance

Each `*.json` file was produced by `research fetch-habous-live`, which fetches
the live Habous page for the city's `ville` id, **requires the mapped Arabic
name to appear on the page** before accepting anything, parses the nine-column
Hijri table, and records source provenance (URL, fetch time, SHA-256). No value
is calculated, interpolated, or invented — a city that cannot be fetched,
verified and parsed is reported, not filled in.

## Regenerating the sec-approach offsets

```
research fit-secaproach \
  --dataset research-data/habous-candidates \
  --base src/main/resources/research/morocco-habous-secaproach-base14.json \
  --output src/main/resources/research/morocco-habous-secaproach.json
```

`--base` preserves the 14 original (locked) offset sets verbatim; only candidate
cities meeting the 95% exact-minute band are written. Cities below the band
(currently berrechid, fkih-ben-salah, larache, sidi-kacem) are reported and
skipped — they receive no offsets and fail closed.
