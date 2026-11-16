from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import trace_player_frames as frames
from trace_player_frames import Section, TimelineRow


def do_frame(token: int, ts: int, dur: int) -> Section:
    return Section(f"Choreographer#doFrame {token}", ts, dur)


class AttributionTest(unittest.TestCase):
    """Hand-written rows in nanoseconds; each frame has a 16 ns budget, so 4 ns of player time makes it heavy."""

    timeline = [
        # Token 1 appears for two layers; the longer row is the late one and must win.
        TimelineRow(1, ts=100, dur=20, jank_type="None", expected_ts=100, expected_dur=16),
        TimelineRow(1, ts=100, dur=30, jank_type="App Deadline Missed, Buffer Stuffing", expected_ts=100,
                    expected_dur=16),
        TimelineRow(2, ts=116, dur=22, jank_type="App Deadline Missed", expected_ts=116, expected_dur=16),
        # Janky for a compositor reason only, and never reached the main thread.
        TimelineRow(3, ts=140, dur=10, jank_type="Buffer Stuffing", expected_ts=132, expected_dur=16),
    ]
    do_frames = [do_frame(0, 80, 10), do_frame(1, 102, 20), do_frame(2, 124, 12)]
    sections = [
        # Before frame 1's expected start and after doFrame 0: belongs to no frame.
        Section("TheoriaPreviewPlayerCreate", 92, 6),
        # Nested: 6 ns of prepare self time around 4 ns of construction.
        Section("TheoriaPreviewPrepare", 104, 10),
        Section("TheoriaPreviewPlayerCreate", 106, 4),
        # Spans the boundary between frame 1 (ends 122) and frame 2.
        Section("TheoriaPreviewSurfaceDetach", 120, 5),
    ]

    def test_intervals_start_at_expected_start_or_previous_do_frame_end(self) -> None:
        intervals = frames.frame_intervals(frames.select_frames(self.timeline), self.do_frames)
        self.assertEqual({1: (100, 122), 2: (122, 136)}, intervals)

    def test_frames_attribute_self_time_without_double_counting(self) -> None:
        results = {result.token: result for result in frames.attribute_frames(self.timeline, self.do_frames,
                                                                               self.sections)}
        self.assertEqual({"TheoriaPreviewPrepare": 6, "TheoriaPreviewPlayerCreate": 4,
                          "TheoriaPreviewSurfaceDetach": 2}, results[1].player_ns)
        self.assertEqual({"TheoriaPreviewSurfaceDetach": 3}, results[2].player_ns)
        self.assertTrue(results[1].late and results[1].player_heavy)
        self.assertTrue(results[2].late)
        self.assertFalse(results[2].player_heavy)
        self.assertIsNone(results[3].interval)

    def test_summary_counts_late_other_jank_and_player_share(self) -> None:
        summary = frames.FrameSummary()
        # A late frame without a doFrame slice counts toward the share but not the joined share.
        unjoined = TimelineRow(4, ts=150, dur=20, jank_type="App Deadline Missed", expected_ts=148, expected_dur=16)
        summary.add(frames.attribute_frames([*self.timeline, unjoined], self.do_frames, self.sections))
        self.assertEqual((4, 3, 2, 1, 2, 1), (summary.frames, summary.late, summary.late_joined, summary.other_jank,
                                              summary.without_do_frame, summary.player_heavy_late))
        self.assertAlmostEqual(1 / 3, summary.player_share)
        self.assertEqual(0.5, summary.player_share_joined)
        self.assertEqual({"construction": 4, "prepare": 6, "surface": 5, "commands_events": 0},
                         summary.late_category_ns)

    def test_section_totals_report_counts_self_time_and_missing_names(self) -> None:
        totals = frames.section_totals(self.sections)
        self.assertEqual({"count": 2, "self_ms": 10 / frames.NS_PER_MS}, totals["TheoriaPreviewPlayerCreate"])
        self.assertEqual(6 / frames.NS_PER_MS, totals["TheoriaPreviewPrepare"]["self_ms"])
        self.assertEqual(0, totals["TheoriaPreviewPlayPause"]["count"])


class SampleShareTest(unittest.TestCase):
    """Frame 1 (late) owns [100, 122) and frame 2 (on time) owns [130, 136); a detach section covers [104, 110)."""

    flush = "androidx.media3.common.util.ListenerSet.flushEvents"
    timeline = [
        TimelineRow(1, ts=100, dur=30, jank_type="App Deadline Missed", expected_ts=100, expected_dur=16),
        TimelineRow(2, ts=130, dur=10, jank_type="None", expected_ts=130, expected_dur=16),
    ]
    do_frames = [do_frame(1, 102, 20), do_frame(2, 131, 5)]
    sections = [Section("TheoriaPreviewSurfaceDetach", 104, 6)]
    samples = [
        frames.Sample(90, flush),  # before every interval
        frames.Sample(105, "androidx.media3.exoplayer.ExoPlayerImpl.setVideoOutputInternal"),  # inside the section
        frames.Sample(112, flush),  # Media3 outside sections
        frames.Sample(116, "androidx.media3.session.MediaSessionImpl.onEvents"),  # while the Viewer plays
        frames.Sample(120, None),  # app work
        frames.Sample(125, flush),  # between intervals
        frames.Sample(132, flush),  # the on-time frame
        frames.Sample(134, None),
    ]

    def test_samples_split_by_frame_and_by_player_work(self) -> None:
        shares = frames.new_sample_shares()
        viewer = frames.Coverage(frames.windows_while_positive([(0, 0), (115, 1), (118, 0)], end=200))
        frames.add_sample_shares(shares, frames.attribute_frames(self.timeline, self.do_frames, self.sections),
                                 self.samples, frames.Coverage(self.sections), viewer)
        late, every = shares["late_frames"], shares["all_frames"]
        self.assertEqual((4, 1, 1, 1), (late.samples, late.inside_sections, late.media3_outside, late.viewer_media3))
        self.assertEqual(0.5, late.player_share)
        self.assertEqual({self.flush: 1}, dict(late.top_outside))
        self.assertEqual((6, 1, 2, 1), (every.samples, every.inside_sections, every.media3_outside,
                                        every.viewer_media3))
        self.assertEqual({self.flush: 2}, dict(every.top_outside))

    def test_counter_windows_run_until_the_next_point_or_the_end(self) -> None:
        self.assertEqual([(10, 20), (30, 50)], [(window.ts, window.end) for window in
                                                frames.windows_while_positive([(30, 1), (10, 2), (20, 0)], end=50)])


class PresentationTest(unittest.TestCase):
    def test_slices_ending_inside_an_abandoned_section_are_abandoned(self) -> None:
        demand, ms = frames.DEMAND_SLICE, frames.NS_PER_MS
        presentations = frames.classify_presentations(
            [Section(demand, 0, 2_500 * ms), Section(demand, 10 * ms, 20 * ms), Section(demand, 3_000 * ms, -1),
             Section(frames.ATTACH_SLICE, 15 * ms, 15 * ms)],
            [Section(frames.ABANDONED_SECTION, 2_499 * ms, 2 * ms)],
        )
        summary = frames.presentation_summary(presentations, "demand")
        self.assertEqual((1, 1, 1, 1), (summary["shown"], summary["abandoned"], summary["abandoned_over_2s"],
                                        summary["open_at_end"]))
        self.assertEqual([20.0], summary["shown_ms"])
        self.assertEqual([2_500.0], summary["abandoned_ms"])
        self.assertEqual(1, frames.presentation_summary(presentations, "attach")["shown"])

    def test_nearest_rank_percentile(self) -> None:
        self.assertEqual(95, frames.nearest_rank(list(range(1, 101)), 0.95))
        self.assertEqual(3, frames.nearest_rank([3, 1, 2], 0.95))


class DeobfuscatorTest(unittest.TestCase):
    mapping = (
        "androidx.media3.exoplayer.ExoPlayerImpl -> c2.a0:\n"
        "    int counter -> a\n"
        # Range 1:5 inlines an app method into setVideoOutputInternal, which is the outer frame.
        "    1:5:void com.app.Helper.check():3:3 -> b\n"
        "    1:5:void setVideoOutputInternal(java.lang.Object):10:14 -> b\n"
        # A class R8 merged into this one owns method c in another range.
        "    1:2:void play():20:21 -> c\n"
        "    3:4:void com.app.Merged.run():7:8 -> c\n"
        "androidx.media3.common.Kept -> androidx.media3.common.Kept:\n"
    )

    def deobfuscator(self) -> frames.Deobfuscator:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mapping.txt"
            path.write_text(self.mapping)
            return frames.Deobfuscator(path)

    def test_frames_resolve_to_the_outer_method_of_each_range(self) -> None:
        deobfuscator = self.deobfuscator()
        self.assertEqual("androidx.media3.exoplayer.ExoPlayerImpl.setVideoOutputInternal", deobfuscator.name("c2.a0.b"))
        self.assertEqual("androidx.media3.exoplayer.ExoPlayerImpl.play|com.app.Merged.run",
                         deobfuscator.name("c2.a0.c"))
        self.assertEqual("androidx.media3.common.Kept.run", deobfuscator.name("androidx.media3.common.Kept.run"))

    def test_only_unambiguous_media3_frames_count(self) -> None:
        deobfuscator = self.deobfuscator()
        self.assertTrue(frames.is_media3(deobfuscator.name("c2.a0.b")))
        self.assertFalse(frames.is_media3(deobfuscator.name("c2.a0.c")))


if __name__ == "__main__":
    unittest.main()
