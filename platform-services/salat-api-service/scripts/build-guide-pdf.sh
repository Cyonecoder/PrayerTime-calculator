#!/usr/bin/env bash
#
# Render a Markdown guide to a styled PDF.
#
# The Markdown is the source of truth — edit it, then run this to refresh the PDF.
# Uses a small dependency-free Markdown-to-HTML step (Python 3 standard library) and
# headless Chrome to print the styled HTML to PDF. No external packages required.
#
# Fenced ```html blocks are passed through verbatim, so a guide can embed a rich
# diagram (e.g. the architecture) that renders as real boxes, not ASCII art.
#
# Usage:  scripts/build-guide-pdf.sh [path/to/file.md]
#         (defaults to docs/PROJECT_GUIDE.md; the PDF is written next to the input)
#
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
md="${1:-$here/docs/PROJECT_GUIDE.md}"
[ -f "$md" ] || md="$here/$1"          # allow a repo-relative argument
html="$(mktemp -t salat-guide.XXXXXX).html"
pdf="${md%.md}.pdf"

[ -f "$md" ] || { echo "Not found: $md" >&2; exit 1; }

# --- Markdown -> styled, print-ready HTML (stdlib only) ----------------------
python3 - "$md" "$html" <<'PY'
import html as H, re, sys, os, base64, mimetypes

src = open(sys.argv[1], encoding="utf-8").read()
base = os.path.dirname(os.path.abspath(sys.argv[1]))
lines = src.split("\n")
out, i, n = [], 0, len(lines)

def inline(t):
    t = H.escape(t)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<em>\1</em>", t)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', t)
    return t

def figure(alt, src_):
    # local images are inlined as data URIs so the PDF is self-contained
    if not re.match(r"^https?:", src_):
        path = src_ if os.path.isabs(src_) else os.path.join(base, src_)
        mime = mimetypes.guess_type(path)[0] or "image/png"
        src_ = "data:%s;base64,%s" % (mime, base64.b64encode(open(path, "rb").read()).decode())
    cap = ("<figcaption>" + inline(alt) + "</figcaption>") if alt else ""
    return '<figure class="fig"><img src="%s" alt="%s">%s</figure>' % (src_, H.escape(alt), cap)

while i < n:
    line = lines[i]
    fence = re.match(r"^\s*```(\w*)\s*$", line)
    if fence:
        lang = fence.group(1); i += 1; buf = []
        while i < n and not re.match(r"^\s*```\s*$", lines[i]):
            buf.append(lines[i]); i += 1
        i += 1
        if lang == "html":                          # verbatim pass-through (diagrams)
            out.append("\n".join(buf))
        else:
            out.append("<pre><code>" + "\n".join(H.escape(b) for b in buf) + "</code></pre>")
    elif re.match(r"^\s*\|.*\|\s*$", line) and i + 1 < n and re.match(r"^\s*\|[\s:|-]+\|\s*$", lines[i+1]):
        header = [c.strip() for c in line.strip().strip("|").split("|")]
        i += 2; rows = []
        while i < n and re.match(r"^\s*\|.*\|\s*$", lines[i]):
            rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")]); i += 1
        t = ["<table><thead><tr>"] + [f"<th>{inline(c)}</th>" for c in header] + ["</tr></thead><tbody>"]
        for r in rows:
            t.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>")
        t.append("</tbody></table>")
        out.append("".join(t))
    elif re.match(r"^!\[[^\]]*\]\([^)]+\)\s*$", line):
        m = re.match(r"^!\[([^\]]*)\]\(([^)]+)\)", line); out.append(figure(m.group(1), m.group(2))); i += 1
    elif re.match(r"^#{1,4}\s", line):
        lvl = len(line) - len(line.lstrip("#")); out.append(f"<h{lvl}>{inline(line[lvl:].strip())}</h{lvl}>"); i += 1
    elif line.strip() in ("---", "***"):
        out.append("<hr>"); i += 1
    elif re.match(r"^\s*[-*]\s", line):
        items = []
        while i < n and re.match(r"^\s*[-*]\s", lines[i]):
            item = re.sub(r"^\s*[-*]\s+", "", lines[i]); i += 1
            while i < n and lines[i].strip() and not re.match(r"^(\s*[-*]\s|\s*\d+\.\s|#{1,4}\s|```|>|\s*\||!\[)", lines[i]):
                item += " " + lines[i].strip(); i += 1
            items.append(f"<li>{inline(item)}</li>")
        out.append("<ul>" + "".join(items) + "</ul>")
    elif re.match(r"^\s*\d+\.\s", line):
        items = []
        while i < n and re.match(r"^\s*\d+\.\s", lines[i]):
            item = re.sub(r"^\s*\d+\.\s+", "", lines[i]); i += 1
            while i < n and lines[i].strip() and not re.match(r"^(\s*[-*]\s|\s*\d+\.\s|#{1,4}\s|```|>|\s*\||!\[)", lines[i]):
                item += " " + lines[i].strip(); i += 1
            items.append(f"<li>{inline(item)}</li>")
        out.append("<ol>" + "".join(items) + "</ol>")
    elif line.strip().startswith(">"):
        buf = []
        while i < n and lines[i].strip().startswith(">"):
            buf.append(re.sub(r"^\s*>\s?", "", lines[i])); i += 1
        out.append("<blockquote>" + inline(" ".join(buf)) + "</blockquote>")
    elif line.strip() == "":
        i += 1
    else:
        buf = []
        while i < n and lines[i].strip() and not re.match(r"^(#{1,4}\s|```|\s*[-*]\s|\s*\d+\.\s|>|\s*\||!\[)", lines[i]) and lines[i].strip() not in ("---","***"):
            buf.append(lines[i]); i += 1
        out.append("<p>" + inline(" ".join(buf)) + "</p>")

body = "\n".join(out)
doc = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>Salat API Service — Project Guide</title>
<style>
  :root {{
    --accent:#2563eb; --accent-dark:#1d4ed8; --accent-soft:#eff4ff;
    --ink:#0f172a; --muted:#475569; --faint:#94a3b8;
    --line:#e2e8f0; --line-2:#cbd5e1; --bg:#f8fafc; --bg-2:#f1f5f9;
    --ok:#15803d; --ok-bg:#e7f6ec; --warn:#b45309; --warn-bg:#fbf1e0; --err:#b91c1c; --err-bg:#fbe9e9;
    --mono:"SF Mono","SFMono-Regular","JetBrains Mono",Menlo,Consolas,monospace;
    --sans:-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Helvetica,Arial,sans-serif;
  }}
  @page {{ size: A4; margin: 17mm 16mm 20mm; }}
  * {{ box-sizing: border-box; }}
  body {{ font-family:var(--sans); color:var(--ink); font-size:10.3pt; line-height:1.55; margin:0;
    -webkit-print-color-adjust:exact; print-color-adjust:exact; }}

  /* title block */
  h1 {{ font-size:25pt; line-height:1.06; letter-spacing:-.02em; margin:0 0 2pt;
    padding-top:4pt; border-top:3pt solid var(--accent); }}
  h1 + p em, h1 ~ p:first-of-type {{ color:var(--muted); }}
  .kicker, h1 {{ position:relative; }}

  h2 {{ font-size:14.5pt; letter-spacing:-.01em; margin:22pt 0 8pt; padding-bottom:5pt;
    border-bottom:1.5pt solid var(--line-2); color:var(--ink); page-break-after:avoid; }}
  h3 {{ font-size:11.5pt; margin:15pt 0 5pt; color:var(--accent-dark); page-break-after:avoid; }}
  h4 {{ font-size:9.6pt; margin:11pt 0 4pt; text-transform:uppercase; letter-spacing:.06em; color:var(--muted); }}
  p {{ margin:6pt 0; }}
  ul, ol {{ margin:6pt 0; padding-left:17pt; }}
  li {{ margin:2.5pt 0; }}
  strong {{ color:var(--ink); font-weight:800; }}
  a {{ color:var(--accent); text-decoration:none; }}
  hr {{ border:none; border-top:1pt solid var(--line); margin:16pt 0; }}

  /* screenshots / figures */
  figure.fig {{ margin:12pt 0; page-break-inside:avoid; }}
  figure.fig img {{ display:block; width:100%; max-width:100%; border:1pt solid var(--line-2);
    border-radius:8px; }}
  figure.fig figcaption {{ margin-top:5pt; font-size:8.2pt; color:var(--muted); text-align:center;
    font-style:italic; }}

  code {{ font-family:var(--mono); font-size:8.8pt; background:var(--bg-2); color:#0b3a86;
    padding:.5pt 3pt; border-radius:3px; }}
  pre {{ background:var(--bg); border:1pt solid var(--line); border-radius:6px; padding:9pt 11pt;
    overflow-x:auto; page-break-inside:avoid; margin:8pt 0; }}
  pre code {{ background:none; color:var(--ink); padding:0; font-size:8.4pt; line-height:1.5; }}

  table {{ border-collapse:collapse; width:100%; margin:8pt 0; font-size:8.8pt; page-break-inside:avoid; }}
  th,td {{ border:.75pt solid var(--line-2); padding:4.5pt 7pt; text-align:left; vertical-align:top; }}
  th {{ background:var(--accent-soft); color:var(--accent-dark); font-size:7.6pt;
    text-transform:uppercase; letter-spacing:.05em; }}
  tbody tr:nth-child(even) td {{ background:var(--bg); }}

  blockquote {{ margin:9pt 0; padding:8pt 12pt; border-left:3pt solid var(--accent);
    background:var(--accent-soft); color:#1e3a8a; border-radius:0 5px 5px 0; page-break-inside:avoid; }}

  /* ---- architecture diagram ---- */
  .arch {{ margin:10pt 0 4pt; page-break-inside:avoid; }}
  .arch-layer {{ display:grid; grid-template-columns:78pt 1fr; gap:9pt; align-items:stretch;
    border:1pt solid var(--line-2); border-radius:8px; background:#fff; padding:8pt 9pt; }}
  .arch-tag {{ font-family:var(--mono); font-size:7.2pt; text-transform:uppercase; letter-spacing:.08em;
    color:#fff; background:var(--accent); border-radius:5px; display:flex; align-items:center;
    justify-content:center; text-align:center; padding:3pt; }}
  .arch-tag.svc {{ background:#0f766e; }} .arch-tag.compute {{ background:#7c3aed; }}
  .arch-tag.store {{ background:#b45309; }} .arch-tag.ext {{ background:#334155; }}
  .arch-nodes {{ display:flex; flex-wrap:wrap; gap:7pt; }}
  .arch-node {{ flex:1 1 auto; min-width:96pt; border:.75pt solid var(--line-2); border-radius:6px;
    background:var(--bg); padding:5pt 8pt; }}
  .arch-node b {{ font-family:var(--mono); font-size:8.6pt; color:var(--ink); }}
  .arch-node span {{ display:block; color:var(--muted); font-size:7.6pt; margin-top:1pt; }}
  .arch-node.hl {{ background:var(--accent-soft); border-color:#bcd0fb; }}
  .arch-flow {{ text-align:center; color:var(--faint); font-family:var(--mono); font-size:7.2pt;
    letter-spacing:.04em; padding:3pt 0; position:relative; }}
  .arch-flow::before {{ content:"\\2193"; display:block; color:var(--accent); font-size:11pt; line-height:1; }}
  .arch-split {{ display:grid; grid-template-columns:1fr 1fr; gap:9pt; }}

  /* ---- TLS contract ---- */
  .tls {{ display:flex; align-items:stretch; gap:0; margin:9pt 0; page-break-inside:avoid; flex-wrap:wrap; }}
  .tls-step {{ flex:1 1 0; min-width:74pt; border:1pt solid var(--line-2); border-radius:7px; background:#fff;
    padding:6pt 8pt; }}
  .tls-step b {{ font-family:var(--mono); font-size:8.4pt; }}
  .tls-step span {{ display:block; color:var(--muted); font-size:7.4pt; margin-top:2pt; }}
  .tls-step.bad {{ border-color:#f0c9c9; background:var(--err-bg); }}
  .tls-step.good {{ border-color:#bcd9c4; background:var(--ok-bg); }}
  .tls-join {{ display:flex; align-items:center; justify-content:center; color:var(--accent);
    font-family:var(--mono); font-size:11pt; width:16pt; flex:none; }}
  .contract {{ display:grid; grid-template-columns:1fr 1fr 1fr; gap:8pt; margin:9pt 0; page-break-inside:avoid; }}
  .contract div {{ border:1pt solid var(--line-2); border-radius:7px; padding:8pt 9pt; }}
  .contract h5 {{ margin:0 0 5pt; font-size:8.2pt; text-transform:uppercase; letter-spacing:.06em; }}
  .contract.k-keep h5, .contract .keep h5 {{ color:var(--ok); }}
  .contract .add h5 {{ color:var(--accent-dark); }} .contract .never h5 {{ color:var(--err); }}
  .contract ul {{ margin:0; padding-left:13pt; font-size:8.2pt; }} .contract li {{ margin:2pt 0; }}
  .contract .keep {{ background:var(--ok-bg); border-color:#bcd9c4; }}
  .contract .add {{ background:var(--accent-soft); border-color:#bcd0fb; }}
  .contract .never {{ background:var(--err-bg); border-color:#f0c9c9; }}

  h2,h3 {{ page-break-inside:avoid; }}
</style></head><body>
{body}
</body></html>"""
open(sys.argv[2], "w", encoding="utf-8").write(doc)
print("html:", sys.argv[2])
PY

# --- HTML -> PDF via headless Chrome -----------------------------------------
chrome=""
for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
         "/Applications/Chromium.app/Contents/MacOS/Chromium" \
         "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser" \
         "$(command -v google-chrome 2>/dev/null || true)" \
         "$(command -v chromium 2>/dev/null || true)"; do
  [ -n "$c" ] && [ -x "$c" ] && { chrome="$c"; break; }
done

if [ -z "$chrome" ]; then
  echo "No headless-capable browser found. The HTML is at: $html" >&2
  echo "Open it and 'Print to PDF', or install Chrome and re-run." >&2
  exit 2
fi

"$chrome" --headless --disable-gpu --no-pdf-header-footer \
  --print-to-pdf="$pdf" "file://$html" >/dev/null 2>&1 || \
"$chrome" --headless --disable-gpu \
  --print-to-pdf="$pdf" "file://$html" >/dev/null 2>&1

rm -f "$html"
echo "Wrote: $pdf"
