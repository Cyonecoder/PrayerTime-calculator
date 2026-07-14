# Sec-approach expansion — accuracy report

Extends the Morocco Habous sec-approach from the 14 original cities to the
scientifically-verified subset of the authoritative 67-city candidate inventory.

- **Model (unchanged):** `publishedMinute = floor((rawSeconds + offsetSeconds[city][prayer]) / 60)`
- **Parameters per city:** six constants (fajr, sunrise, dhuhr, asr, maghrib, isha) — no per-date/-month/-season terms.
- **Oracle:** official Habous timetables, 30 days (2026-06-17..2026-07-16) per city, fetched + Arabic-name-verified via `research fetch-habous-live`. Research/testing only; never emitted as production output.
- **Release gates:** a new city is committed only if it passes every per-city accuracy gate — full-dataset exact ≥90%, **out-of-sample holdout exact ≥85%**, within ±1 min ≥98%, max abs error ≤2 min (see `SecApproachGates`).

> **Authoritative per-city status** is `reports/secapproach-all-cities/new-city-training.json`
> (gate-enforced). The per-city tables further down predate the accuracy gates and
> are kept only for the raw in-sample percentages.

## Coverage summary

| Bucket | Count |
|--------|------:|
| Candidate inventory | 67 |
| Existing sec cities (locked, unchanged) | 14 |
| New cities requiring training | 53 |
| — mapped to a Habous ville page | 46 |
| — **ACCEPTED** (pass all per-city gates) | 34 |
| — FAILED_ACCURACY_GATE (holdout < 85%) | 12 |
| — no Habous page, unmapped (fail closed) | 7 |
| **Total sec-approach cities committed** | **48** |

Aggregate over all 48 committed cities (enforced by `SecApproachReleaseGateTest`):
exact **97.09%**, within ±1 min **100%**, max abs error **1 min**, improvement
over the global reconstructed formula **+17.94 pp**.

> **Honest, out-of-sample estimate.** These are **in-sample** figures on a single
> 30-day **summer** window — not an annual claim. Under a chronological
> train(20d)/validation(5d)/holdout(5d) split (fit on training, ties broken on
> validation, scored untouched on holdout), the 34 accepted cities average
> **≈90.88% exact on the holdout** vs ≈97% in-sample; the 12 cities whose holdout
> fell below 85% are excluded (`FAILED_ACCURACY_GATE`). The shipped offsets are
> refit on all dates (production unchanged); pre-refit holdout metrics are
> preserved in `reports/secapproach-all-cities/holdout-report.json`.

## Newly trained cities — raw in-sample (pre-gate)

| City | Exact-minute | Days |
|------|-------------:|-----:|
| smara | 99.44% | 30 |
| guelmim | 98.89% | 30 |
| al-hoceima | 98.33% | 30 |
| boujdour | 98.33% | 30 |
| essaouira | 98.33% | 30 |
| melilia | 98.33% | 30 |
| sefrou | 98.33% | 30 |
| tarfaya | 98.33% | 30 |
| tiznit | 98.33% | 30 |
| azrou | 97.78% | 30 |
| benslimane | 97.78% | 30 |
| oualidia | 97.78% | 30 |
| taroudant | 97.78% | 30 |
| tinghir | 97.78% | 30 |
| guercif | 97.22% | 30 |
| khemisset | 97.22% | 30 |
| saidia | 97.22% | 30 |
| tiflet | 97.22% | 30 |
| assilah | 96.67% | 30 |
| ifrane | 96.67% | 30 |
| ksar-el-kebir | 96.67% | 30 |
| ouazzane | 96.67% | 30 |
| safi | 96.67% | 30 |
| settat | 96.67% | 30 |
| sidi-bennour | 96.67% | 30 |
| tan-tan | 96.67% | 30 |
| beni-mellal | 96.11% | 30 |
| berkane | 96.11% | 30 |
| bouznika | 96.11% | 30 |
| chefchaouen | 96.11% | 30 |
| el-hajeb | 96.11% | 30 |
| el-jadida | 96.11% | 30 |
| khenifra | 96.11% | 30 |
| khouribga | 96.11% | 30 |
| martil | 96.11% | 30 |
| nador | 96.11% | 30 |
| sebta | 96.11% | 30 |
| sidi-ifni | 96.11% | 30 |
| sidi-slimane | 96.11% | 30 |
| taza | 96.11% | 30 |
| midelt | 95.56% | 30 |
| mohammedia | 95.56% | 30 |

## Excluded — below the 95% band (4)

Fetched and evaluated, but a single constant offset cannot reproduce their
published table to 95% (residual is city-specific minute rounding on the horizon
events). No offsets committed; the sec-approach fails closed for these.

| City | Exact-minute | Days |
|------|-------------:|-----:|
| berrechid | 94.44% | 30 |
| fkih-ben-salah | 94.44% | 30 |
| larache | 93.89% | 30 |
| sidi-kacem | 94.44% | 30 |

## Excluded — no dedicated Habous page (7)

Administratively grouped with a parent city or absent from the official ville
list. No nearest-city/province fallback is permitted, so these stay unmapped and
receive no offsets: bouskoura, kabila, sale, sidi-rahal, tamansourt, tamesna, temara.

## Reproduce

```
research verify-habous-cities            # live re-verification -> verified mapping
research fetch-habous-live --cities all-mapped --output research-data/habous
research train-secaproach \
  --dataset research-data/habous \
  --base src/main/resources/research/morocco-habous-secaproach-base14.json \
  --offsets-out src/main/resources/research/morocco-habous-secaproach.json \
  --report-out reports/secapproach-all-cities/training-report.json
```
