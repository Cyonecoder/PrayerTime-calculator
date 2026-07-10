# Habous method reconstruction — Prompt 7

Continuation of the exact-formula reconstruction of `MOROCCO_HABOUS_RECONSTRUCTED`.
This iteration adds an **elevation-dependent horizon model** to the two sun-at-horizon
prayers (Chourouk/sunrise and Maghrib), which the residual analysis identified as the
single dominant remaining error source.

All numbers below are computed by the research/audit code from the committed reference
dataset (`research-data/habous`, 14 cities × 30 days × 6 prayers = 2520 observations,
17 Jun – 16 Jul 2026). **No Habous timetable is read by, or copied into, the production
calculator** — production (`calculate`/`month`) continues to use `MoroccoCalculationMethod`
(`Source: astronomical`).

## What changed this iteration

The Prompt-6 global formula reached **67.22%** exact-minute agreement. Breaking the
residual down by prayer showed four prayers were essentially solved and all error was
concentrated in the two horizon events:

| Prayer  | exact (before) | within-1min | note |
|---------|---------------:|------------:|------|
| Fajr    | 87.6% | 100% | solved (rounding-boundary residual only) |
| Dhuhr   | 93.1% | 100% | solved |
| Asr     | 87.1% | 100% | solved |
| Isha    | 85.5% | 100% | solved |
| Sunrise | 27.9% |  59% | **systematic, elevation-correlated** |
| Maghrib | 22.1% |  57% | **systematic, elevation-correlated** |

Plotting the per-city mean signed error against city elevation revealed a clean,
monotonic signature — the classic **horizon-dip** effect that the fixed-altitude model
omitted:

```
elevation   sunrise resid.   maghrib resid.   (residual = computed − official, minutes)
 ~15 m        −1.8            +2.0            sea-level cities: sunrise too early, maghrib too late
~466 m         0.0             0.0            the old fixed −1.82°/−2.02° happened to match ~450 m
~1160 m       +1.8            −1.4            high cities: sunrise too late, maghrib too early
```

Higher observer → earlier sunrise and later sunset. This is physical elevation dip, and
the old model applied **none**. The fix models the effective horizon altitude as

```
effective_altitude(event) = base(event) − k(event) · √elevation_metres
```

fitted independently per event (sunrise and Maghrib show different empirical sensitivity,
so they get separate coefficients). The coefficient is a **fitted effective horizon**, not
a claimed physical constant — the standard PrayTimes geometric dip (0.0347·√h) was tested
and is too strong here, especially for Maghrib.

### Frozen parameters (`morocco-habous-reconstructed.json`)

Fitted on the **training** cities only (casablanca, rabat, marrakech, oujda, fez):

| Parameter | Value |
|-----------|------:|
| Fajr angle | 19.068° |
| Isha angle | 17.009° |
| Asr shadow factor | 1.0028 |
| Dhuhr adjustment | +304 s |
| Sunrise base altitude | −1.33° |
| Sunrise dip coefficient | 0.029 °/√m |
| Sunrise adjustment | +45 s |
| Maghrib base altitude | −1.55° |
| Maghrib dip coefficient | 0.023 °/√m |
| Maghrib adjustment | −30 s |
| Rounding | NEAREST_MINUTE |
| Order | RAW → ADJUST → ROUND |

## Results (split-disciplined)

Parameters fitted on TRAINING cities, then evaluated on unseen VALIDATION and HOLDOUT
cities (city-based split, `habous-dataset-split.json`, frozen):

| Split | Exact | Within-1min | Max err | MAE |
|-------|------:|------------:|--------:|----:|
| Training (fit) | 85.89% | 100.00% | 1 min | 0.141 |
| Validation | 68.11% | 95.22% | 2 min | 0.367 |
| Holdout | 76.67% | 95.83% | 2 min | 0.275 |
| **All 14 cities** | **76.90%** | **97.10%** | **2 min** | **0.260** |

Compared with Prompt 6 (all cities): exact **67.22% → 76.90%**, within-1min
**86.11% → 97.10%**, max error **3 → 2 min**.

## Residual classification (582 remaining mismatches, all cities)

| Classification | Count | Share |
|----------------|------:|------:|
| ROUNDING_BOUNDARY | 342 | 58.8% |
| CITY_GROUP_PATTERN (coastal residual) | 167 | 28.7% |
| ELEVATION_OR_HORIZON_EFFECT (unmodelled curvature) | 73 | 12.5% |

The `CITY_GROUP_PATTERN` residual is a ~+1 to +1.5 min sunrise bias concentrated in
low-elevation **coastal/northern** cities (tangier, agadir, tetouan, kenitra, rabat) that
elevation dip alone does not explain — a candidate for a Level‑2 reference-coordinate or
Level‑6 coastal-group refinement. It was **deliberately not fitted** here to avoid
overfitting the 2‑month window.

## Honest limitations — why this is NOT declared exact

The acceptance criteria for an "exact reconstructed model" (100% exact, 0 unresolved) are
**not met**, and the release gate correctly reports **NO-GO**. Beyond that:

- **The dataset is two summer months only** (Jun–Jul 2026). The prompt's own minimum for
  an exact-method claim — ≥10 cities across **all 12 months** and ≥1 complete civil year —
  is not satisfied. The dip *altitude* is season-robust (the engine converts degrees→time
  per date), but the small ± adjustment seconds were fitted on summer data and are unverified
  against winter declination.
- There is **no held-out season**. The city split tests spatial generalization only.
- A pure global formula is unlikely to reach 100%: Habous publishes rounded municipal
  tables that almost certainly carry per-city reference data we cannot reproduce from a
  single formula.

To progress toward a defensible exactness claim, the next step is **more data** (a full
civil year, and a held-out later year), not more parameters on this window.

## Addendum — all-city best-match refit

A later iteration re-fit the method **per prayer on all 14 cities** (rather than
the training-only split above) to maximise exact agreement across every city, as
requested. Each prayer's parameters were optimised independently (the six prayers
are independent given their parameters) for exact-minute count, tie-broken by
within-1-minute then MAE.

Result on all 2520 observations:

| Metric | Split-fit (above) | All-city refit |
|--------|------------------:|---------------:|
| Exact | 76.90% | **80.91%** |
| Within ±1 min | 97.10% | **98.53%** |
| Max error | 2 min | 2 min |
| MAE | 0.260 | **0.206** |

Per prayer (exact): Fajr 89.3%, Dhuhr 93.1%, Asr 92.6%, Isha 86.4%, Maghrib 67.9%,
Chourouk 56.2%.

This is an **in-sample** fit (no held-out cities) — the objective was explicitly
"best match for all cities", so the city split was not used to hold data back. It
is therefore a best smooth-global approximation, not a cross-validated one, and is
still not exact. Investigations that did **not** materially help and were rejected
to avoid memorising the timetable:

- **Per-city reference latitude** (Level 2): optimal shifts were tiny (< 0.05°)
  and lifted overall exact by only ~0.9% — the sunrise residual in the northern
  coastal cities is sunrise-only, which a latitude change (which moves sunrise and
  sunset oppositely) cannot fix.
- **Finer horizon resolution**: sunrise gained only ~0.5%, confirming the ~56%
  sunrise ceiling for a smooth global formula.

The unresolved ±1-minute residual is city-specific published-table rounding, which
is reproduced exactly — and losslessly — by `OFFICIAL`/`AUTO` mode instead.
