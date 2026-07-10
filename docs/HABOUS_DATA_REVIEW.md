# Habous data review process

How official Habous snapshots are adopted, updated, and audited. The guiding rule:
**official data is never changed automatically.** Production output only ever
changes through a reviewed, manual snapshot update.

## Where official data lives

- `src/main/resources/official/habous/*.json` — the accepted, validated snapshots
  served by `OFFICIAL`/`AUTO` mode.
- `src/main/resources/official/habous/index.json` — the manifest (city → file,
  checksum, parser version, coverage).
- `research-data/habous/*.json` — the raw fetched source snapshots (research input).

Each snapshot records `sourceSha256` (source-document checksum), `parserVersion`,
and `fetchedAt` for provenance.

## Source-change monitoring

When freshly-fetched live data differs from the accepted snapshot, the system:

- **does not** update automatically;
- **does not** modify production output automatically;
- produces a **review artifact** via `HabousSnapshotReview`, listing
  added / removed / changed rows (date + prayer, old → new);
- requires **manual approval** before adoption;
- **preserves** the previous snapshot and its checksum history.

`OfficialHabousStore.offer(candidate)` will only replace a stored snapshot with a
candidate that passes full validation, and even then this is used only inside the
reviewed update flow — never as an automatic live overwrite. Invalid candidates
(empty HTML, partial table, wrong city, wrong month, HTTP error page, parser
failure) are rejected and the previous valid snapshot is kept.

## Approval steps

1. Fetch candidate data (opt-in, `live`-tagged tooling — never in normal CI).
2. Generate the review artifact (`HabousSnapshotReview.render`).
3. A human reviews added/removed/changed rows against the official source.
4. If correct, update the snapshot file + `index.json` (new checksum) in a PR.
5. CI runs `habousCompatibilityTest`: `OFFICIAL` mode must remain 100% exact
   against the new committed rows, coverage must stay complete.
6. Merge only on green. History is preserved in version control.

## Coverage and integrity guards

`habousCompatibilityTest` fails when:

- a mapped Habous-supported city lacks a bundled snapshot;
- a snapshot has a gap, duplicate date, or incoherent times;
- an official-mode value no longer matches its committed row;
- a checksum changes without the snapshot file changing accordingly.

## Never

- Never edit an official time after parsing.
- Never round or apply corrections to official values.
- Never auto-adopt live changes.
- Never let a fetch failure overwrite good data.
