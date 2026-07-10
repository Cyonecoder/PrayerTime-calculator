# Habous method reconstruction

How the `MOROCCO_HABOUS_RECONSTRUCTED` calculation method reproduces the Moroccan
Ministry of Habous prayer schedule from first principles (astronomy), and where
it stops being exact.

## Pipeline

```
city + date
  → astronomical solar position (production domain.calculation)
  → reconstructed Habous rules (effective angles/altitudes, dip, adjustments)
  → per-prayer administrative adjustment
  → publication rounding
  → calculated HH:mm
```

The reconstructed method is a **single global formula** applied identically to
every city. It is a research artifact frozen in
`src/main/resources/research/morocco-habous-reconstructed.json` and served in
production only as the `CALCULATED` source mode (parameter version
`morocco-habous-v1`). It never reads any Habous timetable.

## Effective angles and altitudes

| Prayer  | Rule |
|---------|------|
| Fajr    | sun at −19.026° (effective twilight angle) |
| Chourouk (sunrise) | sun at effective horizon `−1.45° − 0.0245·√elevation_m` |
| Dhuhr   | solar transit + administrative adjustment |
| Asr     | shadow factor 1.009 (≈ Standard/Shafi) |
| Maghrib | sun at effective horizon `−1.83° − 0.018·√elevation_m` |
| Isha    | sun at −16.866° (effective twilight angle) |

The sunrise/Maghrib altitudes are an **empirically fitted effective horizon**,
not physical constants. The `k·√elevation` term is the horizon-dip model
(Level 3): higher observers see an earlier sunrise and later sunset. The dip
coefficient was fitted on the training cities only and differs per event because
the observed elevation sensitivity of sunrise and sunset differs. See
`reports/habous-reconstruction-prompt7.md` for the derivation and split-disciplined
results.

## Administrative adjustments

Small whole-second offsets applied after the raw astronomical time and before
rounding:

| Prayer  | Adjustment |
|---------|-----------|
| Dhuhr   | +304 s (transit → published Dhuhr precaution) |
| Chourouk | +53 s |
| Maghrib | −65 s |
| Fajr    | −20 s |
| Asr     | −50 s |
| Isha    | +50 s |

(Base altitude and adjustment are partly interchangeable for the horizon events;
the pair is what is fitted, not each in isolation.)

## Rounding policy and operation order

- **Rounding:** `NEAREST_MINUTE`, applied exactly once.
- **Order:** `RAW → ADJUST → ROUND` (raw seconds, then the administrative
  adjustment in seconds, then a single rounding to the published minute).
- **Timezone:** `Africa/Casablanca` from the JDK tz database, applied exactly
  once (never a hard-coded UTC+1).

## City corrections and official date overrides

The reconstructed method uses **no** per-city corrections and **no** per-date
overrides — it is a pure global formula. Per-city/per-date exactness is provided
separately by the official provider (`OFFICIAL`/`AUTO` modes), not by editing the
formula. Baking official values into the calculation is explicitly avoided so the
calculator stays a method, not a memorized table.

## Accuracy and limitations

Against the committed official data (14 cities × two summer months = 2520
observations), fitted per-prayer on **all** cities to maximise agreement:

- exact-minute agreement **80.91%**, within ±1 min **98.53%**, max error **2 min**,
  MAE **0.206**.
- Per prayer: Fajr 89.3%, Dhuhr 93.1%, Asr 92.6%, Isha 86.4%, Maghrib 67.9%,
  Chourouk 56.2%.

The two horizon events (Chourouk, Maghrib) are the ceiling: a smooth global
formula tops out near 56%/68% exact because the remaining misses are ±1‑minute
city-specific table rounding — the residual is opposite-signed for sunrise vs
Maghrib in some cities and sunrise-only in others, so it is not a single
latitude/coordinate correction. Fitting it away would mean per-city, per-event
minute offsets, i.e. memorising the timetable.

**This method is intentionally not exact and is not claimed to be.** It is fitted
in-sample on these 14 cities / two summer months to be the best *smooth global*
approximation; it is not cross-validated across seasons. For guaranteed exact
output use `OFFICIAL`/`AUTO` mode (`docs/HABOUS_EXACT_COMPATIBILITY.md`), which
reproduces every official minute losslessly.
