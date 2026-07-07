# Habous research dataset

This directory holds the **reference observations** consumed by the parameter-search
engine (`io.github.cyonecoder.prayertime.audit.optimization`). It is *research/audit*
input only — it is **not** a production resource and is **not** on the classpath.

## Provenance

The values in `morocco-2026-07.json` are a verbatim copy of the genuine official
Morocco (Habous) snapshot already committed to the repository as the regression
fixture `src/test/resources/fixtures/reference/morocco-july-2026.json`. No values
were invented or altered; this is the same trustworthy snapshot, re-exported into a
dataset directory so the search engine can load it without depending on a test
resource.

Only the days present in that snapshot are genuine. To search a fuller month, add
more `*.json` files here in the same schema, each populated from a real Habous
fetch — never from synthetic or back-computed values.

## Schema

Each file is a JSON object:

```json
{
  "source": "habous",
  "days": [
    { "city": "casablanca", "date": "2026-07-03",
      "fajr": "04:35", "sunrise": "06:23", "dhuhr": "13:40",
      "asr": "17:19", "maghrib": "20:49", "isha": "22:20" }
  ]
}
```

`city` is a registry slug (see `MoroccoCityRegistry`); `date` is ISO-8601; the six
prayer times are `HH:mm` in Morocco civil time.
