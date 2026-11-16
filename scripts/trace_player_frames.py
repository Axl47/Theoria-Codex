#!/usr/bin/env python3
"""Turn Perfetto traces of the benchmark app into the feed-player numbers of the B3 ExecPlan.

The plan is .docs/exec/video-players-off-main-thread.html; its "What to measure" section owns these definitions.

  frames      report [TRACE ...]      late frames, player-heavy late frames, section time per category,
                                      every player section's count, and time to first frame per presentation
  sampled     sampled TRACE --mapping the share of main-thread samples that are player work, in late frames and
                                      in all frames, split inside and outside player sections
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
  player share          player-heavy late frames over late frames; "joined" divides by late frames with an interval
                        only, since frames without a doFrame slice (RenderThread-only redraws) can never be heavy.
  self time             a player section's duration minus the player sections nested inside it.
  player work           (sampled) a main-thread sample inside a player section, or with a Media3 frame on its
                        stack. Media3 samples outside sections while a Viewer player is active are reported apart and
                        not counted.
  presentation          the TheoriaPreviewDemandToFirstFrame / TheoriaPreviewAttachToFirstFrame asynchronous slices.
                        A slice whose end falls inside a TheoriaPreviewAbandoned section is abandoned; one still open
                        when the trace ends is reported as open.

Sampled capture (`config` prints it): unsampled decision runs use macrobenchmark's default capture. The sampled
run is separate because sampling adds load. Install the benchmark APK, keep the build's R8 mapping file
(app/build/outputs/mapping/benchmarkRelease/mapping.txt) next to it, start the scenario's app process, then start
`adb -s <serial> shell perfetto --txt -c - -o /data/misc/perfetto-traces/player-sampled.pftrace < config.txt`
in the background, drive the scenario, then pull the trace. Start the app first: traced_perf matches a process when it
first samples it, and a process forked after the capture began can still carry the zygote's name then and go without
stacks for the whole capture. The larger perf ring buffer keeps the kernel from dropping samples while stacks are copied.
Trace buffers are sized for a 120 Hz phone: when a ring buffer wraps, the packets that define interned call stacks and
the start-of-trace process scan are overwritten, and trace processor then drops every sample and cannot name the app's
processes. So the process scan has its own buffer that never overwrites, and incremental state is re-emitted every few
seconds so a wrapped buffer still parses.
The benchmark process is profileable, which callstack sampling requires on user builds. Both patterns end in *
because the benchmark fixture runs in the ":benchmarkFixture" process.
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
# CPU-clock sampling only fires while a thread runs, so blocked time inside sections never appears in samples.
SAMPLING_HZ = 500
TRACE_PROCESSOR_VERSION = "v59.1"

SAMPLED_CONFIG = """\
buffers {{ size_kb: 393216 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 65536 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 8192 fill_policy: DISCARD }}
incremental_state_config {{ clear_period_ms: 5000 }}
data_sources {{ config {{ name: "linux.ftrace" target_buffer: 0 ftrace_config {{
  ftrace_events: "sched/sched_switch"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_apps: "{package}*"
  buffer_size_kb: 16384
  drain_period_ms: 250
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" target_buffer: 2 process_stats_config {{
  scan_all_processes_on_start: true
  proc_stats_poll_ms: 10000
}} }} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" target_buffer: 1 }} }}
data_sources {{ config {{ name: "linux.perf" target_buffer: 0 perf_event_config {{
  timebase {{ counter: SW_CPU_CLOCK frequency: {sampling_hz} timestamp_clock: PERF_CLOCK_MONOTONIC }}
  callstack_sampling {{ scope {{ target_cmdline: "{package}*" }} kernel_frames: false }}
  ring_buffer_pages: 2048
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
VIEWER_ACTIVE_COUNTER = "TheoriaViewerActivePlayers"
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
    late_joined: int = 0
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
            self.late_joined += result.interval is not None
            self.player_heavy_late += result.player_heavy
            for name, ns in result.player_ns.items():
                self.late_category_ns[CATEGORY_OF[name]] += ns

    @property
    def late_rate(self) -> float | None:
        return self.late / self.frames if self.frames else None

    @property
    def player_share(self) -> float | None:
        return self.player_heavy_late / self.late if self.late else None

    @property
    def player_share_joined(self) -> float | None:
        return self.player_heavy_late / self.late_joined if self.late_joined else None


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


class Coverage:
    """The union of some sections' intervals, for asking whether a timestamp falls inside any of them."""

    def __init__(self, sections: Iterable[Section]) -> None:
        merged: list[list[int]] = []
        for section in sorted(sections, key=lambda item: item.ts):
            if merged and section.ts <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], section.end)
            else:
                merged.append([section.ts, section.end])
        self.starts = [start for start, _ in merged]
        self.ends = [end for _, end in merged]

    def __contains__(self, ts: int) -> bool:
        index = bisect.bisect_right(self.starts, ts) - 1
        return index >= 0 and ts < self.ends[index]


def windows_while_positive(points: Iterable[tuple[int, float]], end: int) -> list[Section]:
    """Stretches where a counter is above zero; each value holds until the next point, the last one until [end]."""
    ordered = sorted(points)
    windows = []
    for index, (ts, value) in enumerate(ordered):
        until = ordered[index + 1][0] if index + 1 < len(ordered) else end
        if value > 0 and until > ts:
            windows.append(Section("window", ts, until - ts))
    return windows


@dataclass(frozen=True)
class Sample:
    """One main-thread call-stack sample; [media3_frame] is the Media3 frame nearest the leaf, if any."""

    ts: int
    media3_frame: str | None


@dataclass
class SampleShare:
    """Main-thread samples taken inside some frames' main-thread intervals, sorted by whether they are player work.

    Player work is a sample inside a feed-preview player section, or one with a Media3 frame on its stack. Media3
    samples outside every section while a Viewer player is active are counted apart and are not player work: the stack
    cannot say which player they serve, and feed previews are stopped while the Viewer plays.
    """

    samples: int = 0
    inside_sections: int = 0
    media3_outside: int = 0
    viewer_media3: int = 0
    top_outside: dict[str, int] = field(default_factory=lambda: defaultdict(int))

    def add(self, sample: Sample, in_section: bool, viewer_active: bool) -> None:
        self.samples += 1
        if in_section:
            self.inside_sections += 1
        elif sample.media3_frame and viewer_active:
            self.viewer_media3 += 1
        elif sample.media3_frame:
            self.media3_outside += 1
            self.top_outside[sample.media3_frame] += 1

    @property
    def player_share(self) -> float | None:
        return (self.inside_sections + self.media3_outside) / self.samples if self.samples else None

    def to_json(self, top: int) -> dict[str, object]:
        return {
            "samples": self.samples,
            "player_share": self.player_share,
            "inside_sections": self.inside_sections,
            "media3_outside_sections": self.media3_outside,
            "media3_while_viewer_active": self.viewer_media3,
            "top_outside_media3_frames": dict(sorted(self.top_outside.items(), key=lambda item: -item[1])[:top]),
        }


def new_sample_shares() -> dict[str, SampleShare]:
    return {"late_frames": SampleShare(), "all_frames": SampleShare()}


def add_sample_shares(shares: dict[str, SampleShare], results: Iterable[FrameResult], samples: Iterable[Sample],
                      sections: Coverage, viewer: Coverage) -> None:
    """Adds one process's samples to the late-frame and all-frame shares, by the frame interval holding each."""
    intervals = sorted((*result.interval, result.late) for result in results if result.interval)
    starts = [start for start, _, _ in intervals]
    for sample in samples:
        index = bisect.bisect_right(starts, sample.ts) - 1
        if index < 0 or sample.ts >= intervals[index][1]:
            continue
        in_section, viewer_active = sample.ts in sections, sample.ts in viewer
        shares["all_frames"].add(sample, in_section, viewer_active)
        if intervals[index][2]:
            shares["late_frames"].add(sample, in_section, viewer_active)


# ---------------------------------------------------------------------------------------------------------------------
# R8 mapping: names the sampled Java frames of the minified benchmark build.


MAPPING_RANGE = re.compile(r"^(\d+:\d+):")


class Deobfuscator:
    """Maps "obf.Class.method" frame names back through an R8 mapping file.

    Sampled frames carry no line number, and R8 merges classes and inlines methods, so one obfuscated method can hold
    several original ones. Each line range lists its inlined frames innermost first; the range's last line is the
    method the code lives in. A frame resolves to those outer methods, fully qualified and joined with "|".
    """

    def __init__(self, mapping: Path) -> None:
        self.classes: dict[str, str] = {}
        outer: dict[tuple[str, str, str], str] = {}
        current: str | None = None
        for number, line in enumerate(mapping.read_text(encoding="utf-8").splitlines()):
            if not line or line.startswith("#"):
                continue
            if not line.startswith(" "):
                original, _, obfuscated = line.rstrip(":").partition(" -> ")
                self.classes[obfuscated] = original
                current = obfuscated
            elif current and "(" in line and " -> " in line:
                signature, _, obfuscated = line.strip().partition(" -> ")
                line_range = MAPPING_RANGE.match(signature)
                name = signature.split("(")[0].split()[-1]
                qualified = name if "." in name else f"{self.classes[current]}.{name}"
                # Later lines of the same range replace earlier ones; a line without a range stands alone.
                outer[(current, obfuscated, line_range.group(1) if line_range else f"#{number}")] = qualified
        self.methods: dict[tuple[str, str], set[str]] = defaultdict(set)
        for (owner, obfuscated, _), qualified in outer.items():
            self.methods[(owner, obfuscated)].add(qualified)

    def name(self, frame: str) -> str:
        match = re.search(r"([\w$.]+)\.([\w$<>]+)(?:\(|$)", frame)
        if not match:
            return frame
        owner, method = match.groups()
        originals = self.methods.get((owner, method))
        return "|".join(sorted(originals)) if originals else f"{self.classes.get(owner, owner)}.{method}"


def is_media3(frame: str) -> bool:
    """True when every method a resolved frame may be is Media3's."""
    return all(name.startswith("androidx.media3.") for name in frame.split("|"))


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


def app_threads_sql(package: str) -> str:
    """CTEs naming the app's processes (the package and its ":name" processes) and their main threads."""
    return f"""
        with app as (select upid, pid from process where name = '{package}' or name glob '{package}:*'),
        main as (select t.utid, app.upid from thread t join app on t.upid = app.upid and t.tid = app.pid)
    """


@dataclass
class ProcessRows:
    """Everything `report` reads from one app process of one trace."""

    timeline: list[TimelineRow] = field(default_factory=list)
    do_frames: list[Section] = field(default_factory=list)
    player_sections: list[Section] = field(default_factory=list)
    abandoned: list[Section] = field(default_factory=list)
    presentation_slices: list[Section] = field(default_factory=list)


def read_processes(tp: TraceProcessor, package: str) -> dict[int, ProcessRows]:
    """Reads one trace in a single trace_processor run; sections still open at the end are clipped to it."""
    rows = tp.query(app_threads_sql(package) + f"""
        select 'frame' as kind, a.upid, a.name, a.ts, a.dur, a.jank_type, e.ts as expected_ts, e.dur as expected_dur
        from actual_frame_timeline_slice a
        join (select upid, name, min(ts) as ts, max(dur) as dur from expected_frame_timeline_slice
              group by upid, name) e on e.upid = a.upid and e.name = a.name
        where a.upid in (select upid from app)
        union all
        select 'main', m.upid, s.name, s.ts,
               iif(s.dur < 0, (select end_ts from trace_bounds) - s.ts, s.dur), null, null, null
        from slice s join thread_track tt on s.track_id = tt.id join main m on m.utid = tt.utid
        where s.name in ({sql_list([*CATEGORY_OF, ABANDONED_SECTION])}) or s.name glob 'Choreographer#doFrame [0-9]*'
        union all
        select 'async', pt.upid, s.name, s.ts, s.dur, null, null, null
        from slice s join process_track pt on s.track_id = pt.id
        where pt.upid in (select upid from app) and s.name in ({sql_list([DEMAND_SLICE, ATTACH_SLICE])})
    """)
    processes: dict[int, ProcessRows] = defaultdict(ProcessRows)
    for row in rows:
        process = processes[int(row["upid"] or 0)]
        name, ts, dur = row["name"] or "", int(row["ts"] or 0), int(row["dur"] or 0)
        if row["kind"] == "frame":
            process.timeline.append(TimelineRow(int(name), ts, dur, row["jank_type"] or "",
                                                int(row["expected_ts"] or 0), int(row["expected_dur"] or 0)))
        elif row["kind"] == "async":
            process.presentation_slices.append(Section(name, ts, dur))
        elif name == ABANDONED_SECTION:
            process.abandoned.append(Section(name, ts, dur))
        elif name in CATEGORY_OF:
            process.player_sections.append(Section(name, ts, dur))
        elif DO_FRAME.match(name):
            process.do_frames.append(Section(name, ts, dur))
    return processes


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
            "late_frames_joined": summary.late_joined,
            "player_share_joined": summary.player_share_joined,
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
        scenario = scenarios.setdefault(scenario_of(trace), ScenarioTraces())
        scenario.traces.append(str(trace))
        # Frame tokens are vsync ids shared by every process, so each process is joined on its own.
        for process in read_processes(TraceProcessor(args.trace_processor, trace), args.package).values():
            results = attribute_frames(process.timeline, process.do_frames, process.player_sections)
            scenario.summary.add(results)
            scenario.sections.extend(process.player_sections)
            scenario.presentations.extend(classify_presentations(process.presentation_slices, process.abandoned))
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
    print(f"  player-heavy late {values['player_heavy_late_frames']} (player share {fraction(values['player_share'])}, "
          f"{fraction(values['player_share_joined'])} of the {values['late_frames_joined']} joined)")
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
    rows = tp.query(app_threads_sql(args.package) + """
        select ps.ts, ps.callsite_id, m.upid from perf_sample ps join main m on m.utid = ps.utid
        where ps.callsite_id is not null
    """)
    viewer_points: dict[int, list[tuple[int, float]]] = defaultdict(list)
    trace_end = 0
    for row in tp.query(app_threads_sql(args.package) + f"""
        select c.ts, c.value, t.upid, (select end_ts from trace_bounds) as trace_end
        from counter c join process_counter_track t on c.track_id = t.id
        where t.name = '{VIEWER_ACTIVE_COUNTER}' and t.upid in (select upid from app)
    """):
        viewer_points[int(row["upid"] or 0)].append((int(row["ts"] or 0), float(row["value"] or 0)))
        trace_end = int(row["trace_end"] or 0)
    stacks: dict[str, list[str]] = {}

    def stack(callsite: str) -> list[str]:
        """Frame names from leaf to root, memoised per callsite."""
        if callsite not in stacks:
            parent, name = frames.get(callsite, (None, ""))
            stacks[callsite] = [name] + (stack(parent) if parent else [])
        return stacks[callsite]

    samples: dict[int, list[Sample]] = defaultdict(list)
    for row in rows:
        media3 = next((name for name in stack(row["callsite_id"] or "") if is_media3(name)), None)
        samples[int(row["upid"] or 0)].append(Sample(int(row["ts"] or 0), media3))
    totals = new_sample_shares()
    summary = FrameSummary()
    # Frame tokens are vsync ids shared by every process, so each process is joined on its own.
    for upid, process in read_processes(tp, args.package).items():
        results = attribute_frames(process.timeline, process.do_frames, process.player_sections)
        summary.add(results)
        add_sample_shares(totals, results, samples.get(upid, []), Coverage(process.player_sections),
                          Coverage(windows_while_positive(viewer_points.get(upid, []), trace_end)))
    traced_ms = sum(summary.late_category_ns.values()) / NS_PER_MS
    untraced_ms = totals["late_frames"].media3_outside * 1000 / SAMPLING_HZ
    result = {
        "main_thread_samples": len(rows),
        **{key: share.to_json(args.top) for key, share in totals.items()},
        # The plan's Phase 2 check: untraced player time against traced player time, both inside late frames.
        "late_frame_section_self_ms": traced_ms,
        "late_frame_untraced_media3_ms_estimate": untraced_ms,
        "untraced_to_traced": untraced_ms / traced_ms if traced_ms else None,
    }
    if args.json:
        print(json.dumps(result, indent=2))
        return 0
    print(f"main-thread samples {len(rows)}; inside late frames: section self time {traced_ms:.1f} ms, untraced Media3 "
          f"estimate {untraced_ms:.1f} ms (ratio {fraction(result['untraced_to_traced'])})")
    for key in totals:
        values: dict[str, Any] = result[key]  # type: ignore[assignment]
        print(f"{key.replace('_', ' ')}: {values['samples']} samples, player share {fraction(values['player_share'])} "
              f"(inside sections {values['inside_sections']}, Media3 outside sections "
              f"{values['media3_outside_sections']}; Media3 while a Viewer player is active, not counted "
              f"{values['media3_while_viewer_active']})")
        for name, count in values["top_outside_media3_frames"].items():
            print(f"  {count:>6}  {name}")
    return 0


def config(args: argparse.Namespace) -> int:
    print(SAMPLED_CONFIG.format(package=args.package, duration_ms=args.duration_s * 1000, sampling_hz=SAMPLING_HZ),
          end="")
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
