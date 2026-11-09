#!/usr/bin/env python3
"""Turn two live_latency_bench.py result sets into one self-contained before/after page.

    scripts/live_latency_report.py build/live-latency/final --before baseline --after head --html report.html

Every number is the median of the runs in that directory; each chart row can be read in the table at the end.
"""

from __future__ import annotations

import argparse
import html
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

SOURCES = [
    ("source:DANBOORU", "Danbooru"),
    ("source:AIBOORU", "AIBooru"),
    ("source:HITOMI", "Hitomi"),
    ("source:IWARA", "Iwara"),
    ("source:GELBOORU", "Gelbooru"),
    ("source:PIXIV", "Pixiv"),
    ("source:NHENTAI", "nhentai"),
    ("source:RULE34PAHEAL", "Paheal"),
    ("source:RULE34VIDEO", "rule34video"),
    ("source:RULE34GEN", "rule34gen"),
]
TIME_TICKS = [100, 250, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 40_000]
BYTE_TICKS = [10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000, 25_000_000]


def load(directory: Path) -> dict[str, list[dict]]:
    cases: dict[str, list[dict]] = defaultdict(list)
    for line in (directory / "results.jsonl").read_text().splitlines():
        report = json.loads(line)
        cases[report["case"]].append(report)
    return cases


def median_of(reports: list[dict], metric: str) -> float | None:
    values = [report[metric] for report in reports if isinstance(report.get(metric), (int, float))]
    return statistics.median(values) if values else None


def failed_share(reports: list[dict]) -> float:
    return sum(1 for report in reports if report.get("search_ok") is False) / len(reports) if reports else 0.0


def seconds(value: float) -> str:
    if value >= 10_000:
        return f"{value / 1000:.0f} s"
    return f"{value / 1000:.1f} s" if value >= 1000 else f"{value:.0f} ms"


def megabytes(value: float) -> str:
    return f"{value / 1_000_000:.1f} MB" if value >= 1_000_000 else f"{value / 1000:.0f} KB"


def tick_label(value: float, formatter) -> str:
    return formatter(value).replace(".0 ", " ")


class Scale:
    """A log axis, because the values span from a few hundred milliseconds to half a minute."""

    def __init__(self, values: list[float], ticks: list[int]) -> None:
        self.low = min(values) * 0.8
        self.high = max(values) * 1.2
        self.ticks = [tick for tick in ticks if self.low <= tick <= self.high]

    def percent(self, value: float) -> float:
        return 100 * (math.log(value) - math.log(self.low)) / (math.log(self.high) - math.log(self.low))


def dumbbell(title: str, note: str, rows: list[dict], ticks: list[int], formatter) -> str:
    values = [row[side] for row in rows for side in ("before", "after") if row[side] is not None]
    if not values:
        return ""
    scale = Scale(values, ticks)
    body = []
    for row in rows:
        before, after = row["before"], row["after"]
        marks = []
        if before is not None and after is not None:
            left, right = sorted((scale.percent(before), scale.percent(after)))
            marks.append(f'<i class="link" style="left:{left:.2f}%;width:{right - left:.2f}%"></i>')
        for side, value in (("before", before), ("after", after)):
            if value is not None:
                ring = " failed" if row.get(f"{side}_failed") else ""
                marks.append(f'<i class="dot {side}{ring}" style="left:{scale.percent(value):.2f}%"></i>')
        grid = "".join(f'<i class="grid" style="left:{scale.percent(tick):.2f}%"></i>' for tick in scale.ticks)
        reading = " → ".join(
            ("failed at " if row.get(f"{side}_failed") else "") + formatter(row[side]) if row[side] is not None else "none"
            for side in ("before", "after")
        )
        body.append(
            f'<div class="row" tabindex="0" data-tip="{html.escape(row["name"])}: {html.escape(reading)}">'
            f'<div class="name">{html.escape(row["name"])}</div>'
            f'<div class="track">{grid}{"".join(marks)}</div>'
            f'<div class="reading">{html.escape(reading)}</div></div>'
        )
    axis = "".join(
        f'<span style="left:{scale.percent(tick):.2f}%">{html.escape(tick_label(tick, formatter))}</span>' for tick in scale.ticks
    )
    return (
        f'<section><h2>{html.escape(title)}</h2><p class="note">{html.escape(note)}</p>'
        f'{"".join(body)}<div class="row axis"><div class="name"></div><div class="track">{axis}</div>'
        f'<div class="reading"></div></div></section>'
    )


def source_rows(before: dict, after: dict, metric: str, show_failures: bool = False) -> list[dict]:
    rows = []
    for case, name in SOURCES:
        old, new = before.get(case, []), after.get(case, [])
        row = {"name": name, "before": median_of(old, metric), "after": median_of(new, metric)}
        if show_failures:
            row["before_failed"], row["after_failed"] = failed_share(old) > 0.5, failed_share(new) > 0.5
        if row["before"] is not None or row["after"] is not None:
            rows.append(row)
    return rows


def tile(label: str, before: str, after: str, delta: str) -> str:
    return (
        f'<div class="tile"><div class="label">{html.escape(label)}</div><div class="value">{html.escape(after)}</div>'
        f'<div class="delta">was {html.escape(before)} · {html.escape(delta)}</div></div>'
    )


def times_faster(before: float | None, after: float | None) -> str:
    if not before or not after:
        return ""
    ratio = before / after
    return f"{ratio:.0f}× faster" if ratio >= 10 else f"{ratio:.1f}× faster"


def times_smaller(before: float | None, after: float | None) -> str:
    return f"{before / after:.1f}× less data" if before and after else ""


def tiles(before: dict, after: dict) -> str:
    built = []
    for case, label in (("unified:cold", "Search, first card, empty caches"), ("unified:warm", "Reopening the app, first card")):
        old, new = median_of(before.get(case, []), "first_card_ms"), median_of(after.get(case, []), "first_card_ms")
        if old and new:
            built.append(tile(label, seconds(old), seconds(new), times_faster(old, new)))
    failing_before = [name for case, name in SOURCES if failed_share(before.get(case, [])) > 0.5]
    failing_after = [name for case, name in SOURCES if failed_share(after.get(case, [])) > 0.5]
    built.append(tile("Sources failing to load", str(len(failing_before)), str(len(failing_after)), ", ".join(failing_before) or "none"))
    old, new = median_of(before.get("source:HITOMI", []), "cards_rx"), median_of(after.get("source:HITOMI", []), "cards_rx")
    if old and new:
        built.append(tile("12 Hitomi cards, data used", megabytes(old), megabytes(new), times_smaller(old, new)))
    return "".join(built)


def table(before: dict, after: dict) -> str:
    metrics = [
        ("search_ms", "Search", seconds), ("cards_all_ms", "12 card images", seconds), ("cards_rx", "Card data", megabytes),
        ("viewer_all_ms", "Viewer images", seconds), ("pages_all_ms", "Page thumbnails", seconds), ("pages_rx", "Page data", megabytes),
        ("first_card_ms", "First card", seconds), ("first_image_ms", "First image", seconds),
        ("screen_images_ms", "Six images", seconds), ("scroll_wait_p50_ms", "Scroll wait, median", seconds),
    ]
    rows = []
    cases = SOURCES + [("unified:cold", "Unified, empty caches"), ("unified:warm", "Unified, reopened")]
    for case, name in cases:
        old, new = before.get(case, []), after.get(case, [])
        for metric, label, formatter in metrics:
            first, second = median_of(old, metric), median_of(new, metric)
            if first is None and second is None:
                continue
            failed = (failed_share(old), failed_share(new))
            rows.append(
                f"<tr><td>{html.escape(name)}</td><td>{html.escape(label)}</td>"
                f"<td>{formatter(first) if first is not None else '–'}{' (failed)' if metric == 'search_ms' and failed[0] > 0.5 else ''}</td>"
                f"<td>{formatter(second) if second is not None else '–'}{' (failed)' if metric == 'search_ms' and failed[1] > 0.5 else ''}</td>"
                f"<td>{len([r for r in old if metric in r])} / {len([r for r in new if metric in r])}</td></tr>"
            )
    return (
        "<details><summary>Every number as a table</summary><div class='scroll'><table><thead><tr><th>Where</th><th>Measure</th>"
        "<th>Before</th><th>After</th><th>Runs</th></tr></thead><tbody>" + "".join(rows) + "</tbody></table></div></details>"
    )


def environment(directory: Path) -> str:
    path = directory / "environment.jsonl"
    if not path.exists():
        return ""
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    ipv6 = sum(1 for row in rows for value in row["ipv6_connect_ms"].values() if value is not None)
    probes = sum(len(row["ipv6_connect_ms"]) for row in rows)
    return f"IPv6 connected on {ipv6} of {probes} checks during these runs"


STYLE = """
.viz-root{color-scheme:light;--surface:#fcfcfb;--page:#f9f9f7;--ink:#0b0b0b;--ink2:#52514e;--muted:#898781;--grid:#e1e0d9;--axis:#c3c2b7;
  --before:#86b6ef;--after:#1c5cab;--link:#c3c2b7;--ring:rgba(11,11,11,.10)}
@media (prefers-color-scheme:dark){:root:where(:not([data-theme="light"])) .viz-root{color-scheme:dark;--surface:#1a1a19;--page:#0d0d0d;--ink:#f0efec;
  --ink2:#c3c2b7;--muted:#898781;--grid:#2c2c2a;--axis:#383835;--before:#256abf;--after:#9ec5f4;--link:#383835;--ring:rgba(255,255,255,.10)}}
:root[data-theme="dark"] .viz-root{color-scheme:dark;--surface:#1a1a19;--page:#0d0d0d;--ink:#f0efec;--ink2:#c3c2b7;--muted:#898781;--grid:#2c2c2a;
  --axis:#383835;--before:#256abf;--after:#9ec5f4;--link:#383835;--ring:rgba(255,255,255,.10)}
*{box-sizing:border-box}body{margin:0}
.viz-root{background:var(--page);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;padding:24px 16px 48px;min-height:100vh}
.wrap{max-width:900px;margin:0 auto}h1{font-size:24px;margin:0 0 4px}h2{font-size:16px;margin:0 0 2px}
.sub,.note{color:var(--ink2);margin:0}.note{font-size:13px;margin-bottom:12px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:20px 0}
.tile,section,details{background:var(--surface);border:1px solid var(--ring);border-radius:10px;padding:14px 16px}
section,details{margin-top:12px}.label{font-size:13px;color:var(--ink2)}.value{font-size:30px;font-weight:600;line-height:1.25}.delta{font-size:13px;color:var(--ink2)}
.legend{display:flex;gap:16px;font-size:13px;color:var(--ink2);margin:12px 0 0}.legend span{display:inline-flex;align-items:center;gap:6px}
.key{width:10px;height:10px;border-radius:50%;display:inline-block}
.row{display:grid;grid-template-columns:96px minmax(0,1fr) 150px;align-items:center;gap:10px;height:30px;border-radius:6px}
.row:focus-visible{outline:2px solid var(--after);outline-offset:1px}
.name{font-size:13px;white-space:nowrap}.reading{font-size:12px;color:var(--ink2);text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
.track{position:relative;height:100%}.track i{position:absolute;display:block}
.grid{top:0;bottom:0;width:1px;background:var(--grid)}
.link{top:50%;height:2px;margin-top:-1px;background:var(--link)}
.dot{top:50%;width:12px;height:12px;margin:-6px 0 0 -6px;border-radius:50%;box-shadow:0 0 0 2px var(--surface)}
.dot.before{background:var(--before)}.dot.after{background:var(--after)}
.dot.failed{background:var(--surface);border:2px solid var(--before)}
.axis{height:20px}.axis .track span{position:absolute;transform:translateX(-50%);font-size:11px;color:var(--muted);white-space:nowrap;font-variant-numeric:tabular-nums}
#tip{position:fixed;display:none;pointer-events:none;background:var(--ink);color:var(--surface);font-size:12px;padding:4px 8px;border-radius:6px;z-index:5;max-width:280px}
summary{cursor:pointer;font-weight:600}.scroll{overflow-x:auto;margin-top:10px}table{border-collapse:collapse;width:100%;font-size:13px;font-variant-numeric:tabular-nums}
th,td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--grid);white-space:nowrap}
.foot{color:var(--muted);font-size:12px;margin-top:14px}
@media(max-width:560px){.row{grid-template-columns:74px minmax(0,1fr)}.reading{display:none}.axis .track span:nth-child(even){display:none}}
"""

SCRIPT = """
const tip=document.getElementById('tip');
function show(row,x,y){tip.textContent=row.dataset.tip;tip.style.display='block';
 tip.style.left=Math.min(x+12,innerWidth-tip.offsetWidth-8)+'px';tip.style.top=(y+14)+'px'}
document.querySelectorAll('.row[data-tip]').forEach(row=>{
 row.addEventListener('mousemove',e=>show(row,e.clientX,e.clientY));
 row.addEventListener('mouseleave',()=>tip.style.display='none');
 row.addEventListener('focus',()=>{const b=row.getBoundingClientRect();show(row,b.left,b.bottom)});
 row.addEventListener('blur',()=>tip.style.display='none');});
"""


def render(directory: Path, before_name: str, after_name: str) -> str:
    before, after = load(directory / before_name), load(directory / after_name)
    run_count = max(len(reports) for reports in after.values())
    sections = [
        dumbbell("Search: time until results arrive", "Fresh app, no open connections. A hollow dot means the search failed after that long.",
                 source_rows(before, after, "search_ms", show_failures=True), TIME_TICKS, seconds),
        dumbbell("Cards: time until 12 card images have loaded", "Measured once results are in, with empty image caches.",
                 source_rows(before, after, "cards_all_ms"), TIME_TICKS, seconds),
        dumbbell("Cards: data used for 12 card images", "The app's whole traffic over the step, including TLS and headers.",
                 source_rows(before, after, "cards_rx"), BYTE_TICKS, megabytes),
        dumbbell("Galleries: data used for 24 page thumbnails", "What the page scrubber and gallery grid download.",
                 source_rows(before, after, "pages_rx"), BYTE_TICKS, megabytes),
    ]
    note = environment(directory / after_name)
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
        f'<title>Search speed results</title><style>{STYLE}</style></head><body><div class="viz-root"><div class="wrap">'
        f'<h1>Search speed, before and after</h1><p class="sub">Samsung S24+, home Wi-Fi, live provider feeds. Median of {run_count} runs per build, on a debug build.</p>'
        f'<div class="tiles">{tiles(before, after)}</div>'
        '<div class="legend"><span><i class="key" style="background:var(--before)"></i>Before</span>'
        '<span><i class="key" style="background:var(--after)"></i>After</span></div>'
        f'{"".join(sections)}{table(before, after)}<p class="foot">{html.escape(note)}. Axes are logarithmic because values span two orders of magnitude.</p>'
        f'</div><div id="tip" role="status"></div></div><script>{SCRIPT}</script></body></html>'
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--html", type=Path, required=True)
    arguments = parser.parse_args()
    arguments.html.parent.mkdir(parents=True, exist_ok=True)
    arguments.html.write_text(render(arguments.directory, arguments.before, arguments.after))
    print(arguments.html)


if __name__ == "__main__":
    main()
