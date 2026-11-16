#!/usr/bin/env python3
"""Turn Perfetto traces of the benchmark app into the feed-player numbers of the B3 ExecPlan.

The plan is .docs/exec/video-players-off-main-thread.html; its "What to measure" section owns these definitions.

  frames      report [TRACE ...]      late frames, player-heavy late frames, section time per category,
                                      every player section's count, and time to first frame per presentation
  sampled     sampled TRACE --mapping the share of main-thread Media3 samples outside every player section
  config      print the sampled-capture Perfetto configuration below

Usage:
  trace_player_frames.py report --trace-processor /path/to/trace_processor_shell \\
      TheoriaMacrobenchmark_mixedMediaFiveFeedJourney_iter000_*.perfetto-trace [--json] [--late-frames]
  trace_player_frames.py sampled --trace-processor /path/to/trace_processor_shell \\
      --mapping app/build/outputs/mapping/benchmarkRelease/mapping.txt sampled.pftrace [--json]
  trace_player_frames.py config [--package com.theoriacodex.benchmark] [--duration-s 240]

Written against Perfetto v59.1 trace_processor_shell. The path is required so nothing is downloaded at run time.

Definitions (one trace may hold several iterations; `report` groups traces by the scenario in their file name):
  frame                 one actual_frame_timeline_slice row of the app's process per frame token; when a token
                        appears for more than one layer, the longest row is used.
  late frame            a frame whose jank type includes "App Deadline Missed". Frames janky only for other reasons
                        (compositor, display, prediction, buffer stuffing, unknown) are counted as other jank.
  main-thread interval  the frame joins its main-thread "Choreographer#doFrame <token>" slice. The interval ends when
                        that slice ends and starts at the frame's expected start, or at the end of the previous doFrame
                        slice if that is later. Intervals never overlap.
  budget                the frame's own expected duration from the timeline.
  player-heavy          a late frame whose interval holds player section self time of at least a quarter of its budget.
  self time             a player section's duration minus the player sections nested inside it.
  presentation          the TheoriaPreviewDemandToFirstFrame / TheoriaPreviewAttachToFirstFrame asynchronous slices.
                        A slice whose end falls inside a TheoriaPreviewAbandoned section is abandoned; one still open
                        when the trace ends is reported as open.

Sampled capture (`config` prints it): unsampled decision runs use macrobenchmark's default capture. The sampled
run is separate because sampling adds load. Install the benchmark APK, keep the build's R8 mapping file
(app/build/outputs/mapping/benchmarkRelease/mapping.txt) next to it, start
`adb -s <serial> shell perfetto --txt -c - -o /data/misc/perfetto-traces/player-sampled.pftrace < config.txt`
in the background, drive the scenario, then pull the trace. The benchmark process is profileable, which callstack
sampling requires on user builds.
"""
from __future__ import annotations

import argparse
import bisect
import csv
import json
import math
import os
import re
import statistics
import subprocess
import sys
import tempfile
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

DEFAULT_PACKAGE = "com.theoriacodex.benchmark"
TRACE_PROCESSOR_VERSION = "v59.1"

SAMPLED_CONFIG = """\
buffers {{ size_kb: 131072 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 8192 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "linux.ftrace" target_buffer: 0 ftrace_config {{
  ftrace_events: "sched/sched_switch"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_apps: "{package}"
  buffer_size_kb: 16384
  drain_period_ms: 250
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" target_buffer: 1 process_stats_config {{
  scan_all_processes_on_start: true
}} }} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" target_buffer: 1 }} }}
data_sources {{ config {{ name: "linux.perf" target_buffer: 0 perf_event_config {{
  timebase {{ counter: SW_CPU_CLOCK frequency: 500 timestamp_clock: PERF_CLOCK_MONOTONIC }}
  callstack_sampling {{ scope {{ target_cmdline: "{package}" }} kernel_frames: false }}
}} }} }}
duration_ms: {duration_ms}
"""

# Every feed-preview player section, by the plan's four categories. Keep in sync with MediaTraceSections.
CATEGORIES: dict[str, tuple[str, ...]] = {
    "construction": ("TheoriaPreviewPlayerCreate", "TheoriaPreviewViewInflate"),
    "prepare": (
        "TheoriaPreviewPrepare",
        "TheoriaPreviewPlayerPrepare",
        "TheoriaPreviewPlayerRebind",
        "TheoriaPreviewHandoffSeek",
    ),
    "surface": ("TheoriaPreviewSurfaceAttach", "TheoriaPreviewSurfaceDetach"),
    "commands_events": (
        "TheoriaPreviewPlayPause",
        "TheoriaPreviewPlayerRecycle",
        "TheoriaPreviewPlayerResume",
        "TheoriaPreviewPlayerCool",
        "TheoriaPreviewPlayerRelease",
        "TheoriaPreviewStopForViewer",
        "TheoriaPreviewListenerBind",
        "TheoriaPreviewPlayerEvent",
    ),
}
CATEGORY_OF = {name: category for category, names in CATEGORIES.items() for name in names}
DEMAND_SLICE = "TheoriaPreviewDemandToFirstFrame"
ATTACH_SLICE = "TheoriaPreviewAttachToFirstFrame"
ABANDONED_SECTION = "TheoriaPreviewAbandoned"
APP_DEADLINE_MISSED = "App Deadline Missed"
HEAVY_FRACTION = 0.25
LONG_ABANDON_NS = 2_000_000_000
DO_FRAME = re.compile(r"^Choreographer#doFrame (\d+)$")
SCENARIO = re.compile(r"_([A-Za-z0-9]+)_iter\d+")
NS_PER_MS = 1_000_000


# ---------------------------------------------------------------------------------------------------------------------
# Attribution arithmetic: pure functions over rows, unit-tested in scripts/tests/test_trace_player_frames.py.


@dataclass(frozen=True)
class TimelineRow:
    """One actual_frame_timeline_slice row joined to its expected row."""

    token: int
    ts: int
    dur: int
    jank_type: str
    expected_ts: int
    expected_dur: int


@dataclass(frozen=True)
class Section:
    """A synchronous slice on the main thread; [end] is exclusive."""

    name: str
    ts: int
    dur: int

    @property
    def end(self) -> int:
        return self.ts + self.dur


@dataclass(frozen=True)
class Piece:
    """A stretch of one player section's self time."""

    name: str
    start: int
    end: int


@dataclass(frozen=True)
class FrameResult:
    token: int
    late: bool
    other_jank: bool
    budget: int
    interval: tuple[int, int] | None
    player_ns: dict[str, int]

    @property
    def player_total_ns(self) -> int:
        return sum(self.player_ns.values())

    @property
    def player_heavy(self) -> bool:
        return self.late and self.player_total_ns >= self.budget * HEAVY_FRACTION


def select_frames(rows: Iterable[TimelineRow]) -> list[TimelineRow]:
    """One row per token: the longest when a token appears for several layers."""
    longest: dict[int, TimelineRow] = {}
    for row in rows:
        current = longest.get(row.token)
        if current is None or row.dur > current.dur:
            longest[row.token] = row
    return sorted(longest.values(), key=lambda row: row.ts)


def is_late(jank_type: str) -> bool:
    return APP_DEADLINE_MISSED in jank_type


def is_other_jank(jank_type: str) -> bool:
    return not is_late(jank_type) and jank_type.strip() not in ("", "None")


def frame_intervals(frames: list[TimelineRow], do_frames: list[Section]) -> dict[int, tuple[int, int]]:
    """Each frame's main-thread interval, keyed by token; frames without a doFrame slice are absent."""
    ordered = sorted(do_frames, key=lambda section: section.ts)
    previous_end: dict[int, int | None] = {}
    by_token: dict[int, Section] = {}
    for index, section in enumerate(ordered):
        token = int(DO_FRAME.match(section.name).group(1))  # type: ignore[union-attr]
        if token not in by_token:
            by_token[token] = section
            previous_end[token] = ordered[index - 1].end if index else None
    intervals: dict[int, tuple[int, int]] = {}
    for frame in frames:
        section = by_token.get(frame.token)
        if section is None:
            continue
        start = frame.expected_ts
        before = previous_end[frame.token]
        if before is not None:
            start = max(start, before)
        intervals[frame.token] = (min(start, section.end), section.end)
    return intervals


def self_pieces(sections: Iterable[Section]) -> list[Piece]:
    """Splits properly nested sections of one thread into disjoint self-time pieces, sorted by start."""
    ordered = sorted(sections, key=lambda section: (section.ts, -section.dur))
    children: list[list[Section]] = [[] for _ in ordered]
    stack: list[int] = []
    for index, section in enumerate(ordered):
        while stack and ordered[stack[-1]].end <= section.ts:
            stack.pop()
        if stack:
            children[stack[-1]].append(section)
        stack.append(index)
    pieces: list[Piece] = []
    for section, nested in zip(ordered, children):
        cursor = section.ts
        for child in nested:
            if child.ts > cursor:
                pieces.append(Piece(section.name, cursor, child.ts))
            cursor = max(cursor, child.end)
        if section.end > cursor:
            pieces.append(Piece(section.name, cursor, section.end))
    return sorted(pieces, key=lambda piece: piece.start)


def overlap_by_name(pieces: list[Piece], ends: list[int], start: int, end: int) -> dict[str, int]:
    """Self time per section name inside [start, end); [pieces] are disjoint and sorted, [ends] their end times."""
    totals: dict[str, int] = defaultdict(int)
    index = bisect.bisect_right(ends, start)
    while index < len(pieces) and pieces[index].start < end:
        piece = pieces[index]
        covered = min(piece.end, end) - max(piece.start, start)
        if covered > 0:
            totals[piece.name] += covered
        index += 1
    return dict(totals)


def attribute_frames(
    timeline: Iterable[TimelineRow],
    do_frames: list[Section],
    player_sections: Iterable[Section],
) -> list[FrameResult]:
    frames = select_frames(timeline)
    intervals = frame_intervals(frames, do_frames)
    pieces = self_pieces(player_sections)
    ends = [piece.end for piece in pieces]
    results = []
    for frame in frames:
        interval = intervals.get(frame.token)
        player_ns = overlap_by_name(pieces, ends, *interval) if interval else {}
        results.append(FrameResult(
            token=frame.token,
            late=is_late(frame.jank_type),
            other_jank=is_other_jank(frame.jank_type),
            budget=frame.expected_dur,
            interval=interval,
            player_ns=player_ns,
        ))
    return results


@dataclass
class FrameSummary:
    frames: int = 0
    late: int = 0
    other_jank: int = 0
    without_do_frame: int = 0
    player_heavy_late: int = 0
    late_category_ns: dict[str, int] = field(default_factory=lambda: {name: 0 for name in CATEGORIES})

    def add(self, results: Iterable[FrameResult]) -> None:
        for result in results:
            self.frames += 1
            self.other_jank += result.other_jank
            self.without_do_frame += result.interval is None
            if not result.late:
                continue
            self.late += 1
            self.player_heavy_late += result.player_heavy
            for name, ns in result.player_ns.items():
                self.late_category_ns[CATEGORY_OF[name]] += ns

    @property
    def late_rate(self) -> float | None:
        return self.late / self.frames if self.frames else None

    @property
    def player_share(self) -> float | None:
        return self.player_heavy_late / self.late if self.late else None


def section_totals(sections: Iterable[Section]) -> dict[str, dict[str, float]]:
    """Count and self milliseconds for every player section name, including names that never appeared."""
    sections = list(sections)
    counts = {name: 0 for name in CATEGORY_OF}
    self_ns = {name: 0 for name in CATEGORY_OF}
    for section in sections:
        counts[section.name] += 1
    for piece in self_pieces(sections):
        self_ns[piece.name] += piece.end - piece.start
    return {name: {"count": counts[name], "self_ms": self_ns[name] / NS_PER_MS} for name in CATEGORY_OF}


@dataclass(frozen=True)
class Presentation:
    kind: str
    ts: int
    dur_ns: int | None  # None while still open at the end of the trace
    abandoned: bool


def classify_presentations(slices: Iterable[Section], abandoned_sections: Iterable[Section]) -> list[Presentation]:
    marks = sorted(abandoned_sections, key=lambda section: section.ts)
    starts = [mark.ts for mark in marks]
    presentations = []
    for item in slices:
        kind = "demand" if item.name == DEMAND_SLICE else "attach"
        if item.dur < 0:
            presentations.append(Presentation(kind, item.ts, None, abandoned=False))
            continue
        index = bisect.bisect_right(starts, item.end) - 1
        abandoned = index >= 0 and marks[index].ts <= item.end <= marks[index].end
        presentations.append(Presentation(kind, item.ts, item.dur, abandoned))
    return sorted(presentations, key=lambda presentation: presentation.ts)


def nearest_rank(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def presentation_summary(presentations: Iterable[Presentation], kind: str) -> dict[str, object]:
    own = [item for item in presentations if item.kind == kind]
    shown = [item.dur_ns / NS_PER_MS for item in own if item.dur_ns is not None and not item.abandoned]
    abandoned = [item.dur_ns / NS_PER_MS for item in own if item.abandoned and item.dur_ns is not None]
    return {
        "shown": len(shown),
        "p50_ms": statistics.median(shown) if shown else None,
        "p95_ms": nearest_rank(shown, 0.95),
        "max_ms": max(shown) if shown else None,
        "abandoned": len(abandoned),
        "abandoned_over_2s": sum(ms * NS_PER_MS > LONG_ABANDON_NS for ms in abandoned),
        "open_at_end": sum(item.dur_ns is None for item in own),
        "shown_ms": [round(ms, 3) for ms in shown],
        "abandoned_ms": [round(ms, 3) for ms in abandoned],
    }


def union_intervals(sections: Iterable[Section]) -> list[tuple[int, int]]:
    merged: list[list[int]] = []
    for section in sorted(sections, key=lambda item: item.ts):
        if merged and section.ts <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], section.end)
        else:
            merged.append([section.ts, section.end])
    return [(start, end) for start, end in merged]


def count_inside(intervals: list[tuple[int, int]], timestamps: Iterable[int]) -> tuple[int, int]:
    """(inside, outside) counts of [timestamps] against disjoint sorted [intervals]."""
    starts = [start for start, _ in intervals]
    inside = outside = 0
    for ts in timestamps:
        index = bisect.bisect_right(starts, ts) - 1
        if index >= 0 and ts < intervals[index][1]:
            inside += 1
        else:
            outside += 1
    return inside, outside


# ---------------------------------------------------------------------------------------------------------------------
# R8 mapping: names the sampled Java frames of the minified benchmark build.


class Deobfuscator:
    """Maps "obf.Class.method" frame names back through an R8 mapping file."""

    def __init__(self, mapping: Path) -> None:
        self.classes: dict[str, str] = {}
        self.methods: dict[tuple[str, str], set[str]] = defaultdict(set)
        current: str | None = None
        for line in mapping.read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            if not line.startswith(" "):
                original, _, obfuscated = line.rstrip(":").partition(" -> ")
                self.classes[obfuscated] = original
                current = obfuscated
            elif current and "(" in line and " -> " in line:
                signature, _, obfuscated = line.strip().partition(" -> ")
                name = signature.split("(")[0].split()[-1].split(":")[-1]
                self.methods[(current, obfuscated)].add(name.rsplit(".", 1)[-1])

    def name(self, frame: str) -> str:
        match = re.search(r"([\w$.]+)\.([\w$<>]+)(?:\(|$)", frame)
        if not match:
            return frame
        owner, method = match.groups()
        originals = self.methods.get((owner, method))
        resolved = "|".join(sorted(originals)) if originals else method
        return f"{self.classes.get(owner, owner)}.{resolved}"


# ---------------------------------------------------------------------------------------------------------------------
# Trace reading.


class TraceProcessor:
    def __init__(self, binary: Path, trace: Path) -> None:
        self.binary = binary
        self.trace = trace

    def query(self, sql: str) -> list[dict[str, str | None]]:
        with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as handle:
            handle.write(sql)
            path = handle.name
        try:
            result = subprocess.run([str(self.binary), "-q", path, str(self.trace)], capture_output=True, text=True)
        finally:
            os.unlink(path)
        if result.returncode != 0:
            raise SystemExit(f"trace_processor_shell failed on {self.trace}: {result.stderr.strip()[-2000:]}")
        lines = [line for line in result.stdout.splitlines() if line.strip() and not line.startswith("Query executed")]
        return [{key: (None if value == "[NULL]" else value) for key, value in row.items()}
                for row in csv.DictReader(lines)]


def sql_list(names: Iterable[str]) -> str:
    return ", ".join(f"'{name}'" for name in names)


def main_thread_filter(package: str) -> str:
    return (f"t.upid = (select upid from process where name = '{package}' order by start_ts desc limit 1) "
            "and t.tid = (select pid from process where upid = t.upid)")


def read_main_sections(tp: TraceProcessor, package: str, names: Iterable[str]) -> list[Section]:
    rows = tp.query(f"""
        select s.name, s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
        where {main_thread_filter(package)} and s.name in ({sql_list(names)})
    """)
    end = trace_end(tp)
    return [Section(row["name"] or "", int(row["ts"] or 0),
                    int(row["dur"] or 0) if int(row["dur"] or 0) >= 0 else end - int(row["ts"] or 0))
            for row in rows]


def trace_end(tp: TraceProcessor) -> int:
    return int(tp.query("select end_ts from trace_bounds")[0]["end_ts"] or 0)


def read_frames(tp: TraceProcessor, package: str) -> tuple[list[TimelineRow], list[Section]]:
    timeline = tp.query(f"""
        select cast(a.name as int) as token, a.ts, a.dur, a.jank_type, e.ts as expected_ts, e.dur as expected_dur
        from actual_frame_timeline_slice a
        join (select upid, name, min(ts) as ts, max(dur) as dur from expected_frame_timeline_slice group by upid, name) e
          on e.upid = a.upid and e.name = a.name
        where a.upid = (select upid from process where name = '{package}' order by start_ts desc limit 1)
    """)
    do_frames = tp.query(f"""
        select s.name, s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
        where {main_thread_filter(package)} and s.name glob 'Choreographer#doFrame [0-9]*' and s.dur >= 0
    """)
    rows = [TimelineRow(int(row["token"] or 0), int(row["ts"] or 0), int(row["dur"] or 0), row["jank_type"] or "",
                        int(row["expected_ts"] or 0), int(row["expected_dur"] or 0)) for row in timeline]
    sections = [Section(row["name"] or "", int(row["ts"] or 0), int(row["dur"] or 0))
                for row in do_frames if DO_FRAME.match(row["name"] or "")]
    return rows, sections


def read_presentations(tp: TraceProcessor, package: str) -> list[Presentation]:
    rows = tp.query(f"""
        select s.name, s.ts, s.dur from slice s join process_track pt on s.track_id = pt.id
        where pt.upid = (select upid from process where name = '{package}' order by start_ts desc limit 1)
          and s.name in ({sql_list([DEMAND_SLICE, ATTACH_SLICE])})
    """)
    slices = [Section(row["name"] or "", int(row["ts"] or 0), int(row["dur"] or 0)) for row in rows]
    return classify_presentations(slices, read_main_sections(tp, package, [ABANDONED_SECTION]))


def scenario_of(trace: Path) -> str:
    match = SCENARIO.search(trace.name)
    return match.group(1) if match else trace.stem


def fraction(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.1%}"


def milliseconds(value: object) -> str:
    return "n/a" if value is None else f"{value:.1f} ms"


@dataclass
class ScenarioTraces:
    traces: list[str] = field(default_factory=list)
    summary: FrameSummary = field(default_factory=FrameSummary)
    sections: list[Section] = field(default_factory=list)
    presentations: list[Presentation] = field(default_factory=list)
    late_frames: list[dict[str, object]] = field(default_factory=list)

    def to_json(self, late_frames: bool) -> dict[str, object]:
        summary = self.summary
        values: dict[str, object] = {
            "traces": self.traces,
            "frames": summary.frames,
            "late_frames": summary.late,
            "late_rate": summary.late_rate,
            "other_jank_frames": summary.other_jank,
            "frames_without_do_frame": summary.without_do_frame,
            "player_heavy_late_frames": summary.player_heavy_late,
            "player_share": summary.player_share,
            "late_frame_section_ms": {name: ns / NS_PER_MS for name, ns in summary.late_category_ns.items()},
            "sections": section_totals(self.sections),
            "demand_to_first_frame": presentation_summary(self.presentations, "demand"),
            "attach_to_first_frame": presentation_summary(self.presentations, "attach"),
        }
        if late_frames:
            values["late_frame_detail"] = self.late_frames
        return values


def read_scenarios(args: argparse.Namespace) -> dict[str, ScenarioTraces]:
    scenarios: dict[str, ScenarioTraces] = {}
    for trace in args.traces:
        tp = TraceProcessor(args.trace_processor, trace)
        timeline, do_frames = read_frames(tp, args.package)
        sections = read_main_sections(tp, args.package, CATEGORY_OF)
        results = attribute_frames(timeline, do_frames, sections)
        scenario = scenarios.setdefault(scenario_of(trace), ScenarioTraces())
        scenario.traces.append(str(trace))
        scenario.summary.add(results)
        scenario.sections.extend(sections)
        scenario.presentations.extend(read_presentations(tp, args.package))
        scenario.late_frames.extend(
            {"trace": trace.name, "token": result.token, "interval_ns": result.interval,
             "budget_ms": result.budget / NS_PER_MS,
             "player_ms": {name: ns / NS_PER_MS for name, ns in result.player_ns.items()},
             "player_heavy": result.player_heavy}
            for result in results if result.late
        )
    return scenarios


def print_scenario(scenario: str, values: dict[str, Any]) -> None:
    print(f"{scenario} ({len(values['traces'])} trace(s))")
    print(f"  frames {values['frames']}, late {values['late_frames']} ({fraction(values['late_rate'])}), "
          f"other jank {values['other_jank_frames']}, without doFrame {values['frames_without_do_frame']}")
    print(f"  player-heavy late {values['player_heavy_late_frames']} (player share {fraction(values['player_share'])})")
    print("  section self time inside late frames: " + ", ".join(
        f"{name} {ms:.1f} ms" for name, ms in values["late_frame_section_ms"].items()))
    print("  sections (count, self ms):")
    for name, totals in values["sections"].items():
        missing = "  <- none" if not totals["count"] else ""
        print(f"    {CATEGORY_OF[name]:<16} {name:<32} {totals['count']:>6} {totals['self_ms']:>10.1f}{missing}")
    for key in ("demand_to_first_frame", "attach_to_first_frame"):
        item = values[key]
        print(f"  {key}: shown {item['shown']}, p50 {milliseconds(item['p50_ms'])}, "
              f"p95 {milliseconds(item['p95_ms'])}, max {milliseconds(item['max_ms'])}, "
              f"abandoned {item['abandoned']} ({item['abandoned_over_2s']} over 2 s), open {item['open_at_end']}")
        print(f"    per presentation ms: {item['shown_ms']}")
        if item["abandoned_ms"]:
            print(f"    abandoned ms: {item['abandoned_ms']}")
    for frame in values.get("late_frame_detail", []):
        print(f"  late {frame['trace']} token {frame['token']} interval {frame['interval_ns']} "
              f"budget {frame['budget_ms']:.2f} ms player {frame['player_ms']} heavy {frame['player_heavy']}")


def report(args: argparse.Namespace) -> int:
    output = {name: scenario.to_json(args.late_frames) for name, scenario in read_scenarios(args).items()}
    if args.json:
        print(json.dumps(output, indent=2))
    else:
        for scenario, values in output.items():
            print_scenario(scenario, values)
    return 0


def sampled(args: argparse.Namespace) -> int:
    tp = TraceProcessor(args.trace_processor, args.trace)
    deobfuscator = Deobfuscator(args.mapping)
    frames = {row["id"]: (row["parent_id"], deobfuscator.name(row["name"] or ""))
              for row in tp.query("""
                  select c.id, c.parent_id, f.name from stack_profile_callsite c
                  join stack_profile_frame f on c.frame_id = f.id
              """)}
    samples = tp.query(f"""
        select ps.ts, ps.callsite_id from perf_sample ps join thread t using(utid)
        where {main_thread_filter(args.package)} and ps.callsite_id is not null
    """)
    sections = union_intervals(read_main_sections(tp, args.package, CATEGORY_OF))
    stacks: dict[str, list[str]] = {}

    def stack(callsite: str) -> list[str]:
        """Frame names from leaf to root, memoised per callsite."""
        if callsite not in stacks:
            parent, name = frames.get(callsite, (None, ""))
            stacks[callsite] = [name] + (stack(parent) if parent else [])
        return stacks[callsite]

    media3_samples = []
    for sample in samples:
        media3 = [name for name in stack(sample["callsite_id"] or "") if name.startswith("androidx.media3.")]
        if media3:
            media3_samples.append((int(sample["ts"] or 0), media3[0]))
    inside, outside = count_inside(sections, (ts for ts, _ in media3_samples))
    starts = [start for start, _ in sections]
    top: dict[str, int] = defaultdict(int)
    for ts, leaf in media3_samples:
        index = bisect.bisect_right(starts, ts) - 1
        if index < 0 or ts >= sections[index][1]:
            top[leaf] += 1
    result = {
        "main_thread_samples": len(samples),
        "media3_samples_inside_sections": inside,
        "media3_samples_outside_sections": outside,
        "outside_share": outside / (inside + outside) if inside + outside else None,
        "top_outside_media3_frames": dict(sorted(top.items(), key=lambda item: -item[1])[:args.top]),
    }
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"main-thread samples {result['main_thread_samples']}; Media3 samples inside player sections "
              f"{inside}, outside {outside} (outside share {fraction(result['outside_share'])})")
        for name, count in result["top_outside_media3_frames"].items():
            print(f"  {count:>6}  {name}")
    return 0


def config(args: argparse.Namespace) -> int:
    print(SAMPLED_CONFIG.format(package=args.package, duration_ms=args.duration_s * 1000), end="")
    return 0


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("report", "sampled"):
        command = commands.add_parser(name)
        command.add_argument("--trace-processor", type=Path, required=True,
                             help=f"trace_processor_shell binary (written against {TRACE_PROCESSOR_VERSION})")
        command.add_argument("--package", default=DEFAULT_PACKAGE)
        command.add_argument("--json", action="store_true")
    commands.choices["report"].add_argument("traces", type=Path, nargs="+")
    commands.choices["report"].add_argument("--late-frames", action="store_true",
                                            help="list every late frame for a hand check")
    commands.choices["sampled"].add_argument("trace", type=Path)
    commands.choices["sampled"].add_argument("--mapping", type=Path, required=True, help="the build's R8 mapping.txt")
    commands.choices["sampled"].add_argument("--top", type=int, default=15)
    config_command = commands.add_parser("config")
    config_command.add_argument("--package", default=DEFAULT_PACKAGE)
    config_command.add_argument("--duration-s", type=int, default=240)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    return {"report": report, "sampled": sampled, "config": config}[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
