# Legacy Habous snapshot (quarantined)

`morocco-2026-07.json` was previously the reference dataset, labelled a "genuine
official Habous snapshot re-exported from the committed regression fixture."

On 2026-07-07 it was cross-checked against the **live** official Habous source
(https://www.habous.gov.ma/prieres/) for the overlapping rabat dates
2026-07-03..09 and found to disagree on 6 of 7 days (differences up to ~1 minute
across Fajr, Sunrise, Asr, Maghrib). It is therefore **not** consistent with the
current official published times and has been quarantined here so it no longer
acts as reference truth. The authoritative reference is now the live-collected
data under `research-data/habous/` (see each file's `source`, `sourceUrl` and
`sourceSha256` provenance).
