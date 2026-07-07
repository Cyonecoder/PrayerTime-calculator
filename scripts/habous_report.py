#!/usr/bin/env python3
"""Regenerate the Habous calculated-vs-official comparison report for a date range.

Two data sources are combined:

  * Calculated times — produced by the project's own engine for ANY date range,
    via  ./gradlew run --args="range --city <c> --from <d> --to <d>".
  * Official times — fetched live from habous.gov.ma. IMPORTANT: the live Habous
    page (index.php?ville=<id>) only serves the *current Hijri month*; there is
    no date/month parameter. So an official comparison is only possible for the
    dates the site is currently publishing. For any other range the report is
    calculated-only (or uses official data you have already archived — see
    `research fetch-habous`).

Verified Habous `ville` ids are read from
src/main/resources/research/habous-city-mapping.json (verified entries only).

Usage:
  python3 scripts/habous_report.py --from 2026-06-17 --to 2026-07-16
  python3 scripts/habous_report.py --from 2026-06-17 --to 2026-07-16 \
        --cities rabat,casablanca --out reports/june-july.html
  python3 scripts/habous_report.py --from 2027-01-01 --to 2027-01-31 --no-fetch
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAPPING = os.path.join(REPO, "src/main/resources/research/habous-city-mapping.json")
HABOUS_URL = "https://www.habous.gov.ma/prieres/index.php?ville={ville}"
USER_AGENT = "PrayerTime-Calculator-Research/0.1 (+report script)"
PRAYERS = ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"]
LABEL = {"fajr": "Fajr", "sunrise": "Sunrise", "dhuhr": "Dhuhr",
         "asr": "Asr", "maghrib": "Maghrib", "isha": "Isha"}
DISPLAY = {"rabat": "Rabat", "casablanca": "Casablanca", "fez": "Fès",
           "laayoune": "Laâyoune"}


def load_mapping():
    """verified application-city -> {ville, arabic}."""
    data = json.load(open(MAPPING, encoding="utf-8"))
    out = {}
    for e in data["mappings"]:
        if e.get("verified"):
            out[e["applicationCityId"]] = {
                "ville": e["habousCityId"], "arabic": e["habousArabicName"]}
    return out


def daterange(a, b):
    d = a
    while d <= b:
        yield d
        d += dt.timedelta(days=1)


def run_calc(city, frm, to):
    """calculated times per date via the range CLI: {iso_date: {prayer: HH:MM}}."""
    gradlew = os.path.join(REPO, "gradlew")
    args = f'range --city {city} --from {frm} --to {to}'
    proc = subprocess.run([gradlew, "-q", "run", f"--args={args}"],
                          cwd=REPO, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"gradle range failed for {city}:\n{proc.stderr}")
    out = {}
    row_re = re.compile(r'^(\d{4}-\d{2}-\d{2})' + r'\s+(\d{1,2}:\d{2})' * 6)
    for line in proc.stdout.splitlines():
        m = row_re.match(line)
        if m:
            out[m.group(1)] = dict(zip(PRAYERS, m.groups()[1:]))
    return out


def fetch_official(ville, timeout=25):
    """Parse the live Muharram-month page into {iso_date: {prayer: HH:MM}}.

    The page rows carry [weekday, hijri-day, gregorian-day, +6 times]; the
    Gregorian day rolls 30->1 at the month boundary, which we use to advance the
    Gregorian month. The current year is assumed (the page shows the current
    Hijri month)."""
    # curl is used rather than urllib: it is more reliable across platforms for
    # TLS trust (macOS system Python often lacks a CA bundle) and honours the OS
    # certificate store.
    url = HABOUS_URL.format(ville=ville)
    proc = subprocess.run(
        ["curl", "-sS", "-m", str(timeout), "-A", USER_AGENT, url],
        capture_output=True, text=True)
    if proc.returncode != 0:
        raise ValueError(f"curl failed ({proc.returncode}): {proc.stderr.strip()}")
    html = proc.stdout
    tbl = re.search(r'<table id="horaire".*?</table>', html, re.S)
    if not tbl:
        raise ValueError("no #horaire table in Habous page")
    strip = lambda c: re.sub(r'<[^>]+>', '', c).strip()
    parsed = []                          # (gregorian_day, {prayer: HH:MM})
    for r in re.findall(r'<tr.*?</tr>', tbl.group(0), re.S):
        cells = [strip(c) for c in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', r, re.S)]
        if len(cells) >= 9 and cells[2].isdigit():
            parsed.append((int(cells[2]), dict(zip(PRAYERS, cells[3:9]))))
    if not parsed:
        raise ValueError("no day rows in Habous page")

    # The page shows the current Hijri month as a contiguous run of Gregorian
    # days that may cross a month boundary (…30, 1, 2…). Split at the rollover,
    # then anchor the two segments to real Gregorian months using today's date,
    # which is guaranteed to fall inside the currently-published month.
    days = [d for d, _ in parsed]
    roll = next((i for i in range(1, len(days)) if days[i] < days[i - 1]), None)
    seg1 = set(days[:roll]) if roll is not None else set(days)
    today = dt.date.today()

    def month_year(day, in_first_segment):
        if roll is None:
            m, y = today.month, today.year
        elif today.day in seg1:          # today is in the first (earlier) month
            m = today.month if in_first_segment else today.month + 1
            y = today.year
        else:                            # today is in the second (later) month
            m = today.month - 1 if in_first_segment else today.month
            y = today.year
        if m == 13:
            m, y = 1, y + 1
        if m == 0:
            m, y = 12, y - 1
        return m, y

    out, seen_roll = {}, False
    prev = None
    for gday, times in parsed:
        if prev is not None and gday < prev:
            seen_roll = True
        prev = gday
        m, y = month_year(gday, not seen_roll)
        try:
            out[dt.date(y, m, gday).isoformat()] = times
        except ValueError:
            continue
    return out


def to_min(t):
    h, m = t.split(":")
    return int(h) * 60 + int(m)


def stats(rows):
    if not rows:
        return {"n": 0, "exact": 0, "within1": 0, "mae": 0, "max": 0, "bias": 0}
    ds = [abs(r["diff"]) for r in rows]
    return {"n": len(rows), "exact": sum(x == 0 for x in ds),
            "within1": sum(x <= 1 for x in ds), "mae": round(sum(ds) / len(ds), 2),
            "max": max(ds), "bias": round(sum(r["diff"] for r in rows) / len(rows), 2)}


def build_payload(frm, to, cities, mapping, fetch):
    dates = [d.isoformat() for d in daterange(frm, to)]
    calc, official = {}, {}
    for c in cities:
        calc[c] = {d: v for d, v in run_calc(c, frm, to).items() if d in dates}
        if fetch and c in mapping:
            try:
                alloff = fetch_official(mapping[c]["ville"])
                official[c] = {d: alloff[d] for d in dates if d in alloff}
            except Exception as e:                       # noqa: BLE001
                print(f"  ! official fetch failed for {c}: {e}", file=sys.stderr)
                official[c] = {}
        else:
            official[c] = {}
    comp = []
    for c in cities:
        for d in dates:
            if d in calc[c] and d in official.get(c, {}):
                for p in PRAYERS:
                    cv, ov = calc[c][d][p], official[c][d][p]
                    comp.append({"city": c, "date": d, "prayer": p,
                                 "calc": cv, "official": ov,
                                 "diff": to_min(cv) - to_min(ov)})
    return {
        "from": frm.isoformat(), "to": to.isoformat(), "dates": dates,
        "cities": cities, "calc": calc, "official": official, "comp": comp,
        "ids": {c: mapping.get(c, {}).get("ville", "?") for c in cities},
        "arabic": {c: mapping.get(c, {}).get("arabic", "") for c in cities},
        "display": {c: DISPLAY.get(c, c.title()) for c in cities},
        "overall": stats(comp),
        "perPrayer": {p: stats([r for r in comp if r["prayer"] == p]) for p in PRAYERS},
        "perCity": {c: stats([r for r in comp if r["city"] == c]) for c in cities},
        "generatedAt": dt.datetime.now().strftime("%Y-%m-%d %H:%M"),
        "officialCoverage": sum(len(official.get(c, {})) for c in cities),
    }


def render(payload):
    here = os.path.dirname(os.path.abspath(__file__))
    tpl = open(os.path.join(here, "habous_report_template.html"), encoding="utf-8").read()
    return tpl.replace("__DATA__", json.dumps(payload, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser(description="Regenerate the Habous comparison report.")
    ap.add_argument("--from", dest="frm", required=True, help="start date YYYY-MM-DD")
    ap.add_argument("--to", dest="to", required=True, help="end date YYYY-MM-DD")
    ap.add_argument("--cities", default="", help="comma list (default: all verified)")
    ap.add_argument("--out", default=os.path.join(REPO, "reports/habous-comparison.html"))
    ap.add_argument("--no-fetch", dest="fetch", action="store_false",
                    help="skip live official fetch (calculated-only report)")
    args = ap.parse_args()

    frm = dt.date.fromisoformat(args.frm)
    to = dt.date.fromisoformat(args.to)
    if to < frm:
        raise SystemExit("--to is before --from")

    mapping = load_mapping()
    cities = ([c.strip().lower() for c in args.cities.split(",") if c.strip()]
              or list(mapping.keys()))

    print(f"Range {frm}..{to}  cities={cities}  fetch={args.fetch}")
    payload = build_payload(frm, to, cities, mapping, args.fetch)
    print(f"  calculated: {sum(len(payload['calc'][c]) for c in cities)} city-days")
    print(f"  official coverage: {payload['officialCoverage']} city-days "
          f"({payload['overall']['n']} comparable observations)")
    if args.fetch and payload["officialCoverage"] == 0:
        print("  note: the live Habous site only serves the current Hijri month, "
              "so no official data overlaps this range — report is calculated-only.")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    open(args.out, "w", encoding="utf-8").write(render(payload))
    print(f"Wrote {args.out}")


if __name__ == "__main__":
    main()
