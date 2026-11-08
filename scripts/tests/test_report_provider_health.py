from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest

SCRIPTS_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import report_provider_health as rph  # noqa: E402

RUN = "https://github.com/o/r/actions/runs/1"
TODAY = "2026-01-01"


def report(**sources: tuple[str, list[tuple[str, str, str | None]]]) -> dict:
    """sources: id -> (aggregate status, [(check, step status, reason)])."""
    return {
        "liveProvidersEnabled": True,
        "results": [{"source": s, "checkName": "source-probe", "status": v[0]} for s, v in sources.items()],
        "probeResults": [
            {"source": s, "checkName": c, "status": st, **({"failureReason": r} if r else {})}
            for s, v in sources.items()
            for c, st, r in v[1]
        ],
    }


BAD = ("FAILED", [("newest-search", "FAILED", "NETWORK")])
OK = ("OK", [("newest-search", "OK", None)])
SKIP = ("SKIPPED", [("credentials", "SKIPPED", None)])
CHALLENGED = ("CANNOT_CHECK", [("newest-search", "CANNOT_CHECK", None)])


def health(**sources):
    return rph.source_health(report(**sources))


def issue(number: int, source: str, h: rph.SourceHealth | None = None, body: str = "") -> dict:
    if h is not None:
        body = rph.render_body(source, h, RUN, TODAY)
    return {"number": number, "title": rph.TITLE_PREFIX + source, "body": body}


class PlanTest(unittest.TestCase):
    def plan(self, first, confirm, issues):
        return rph.plan(first, confirm, issues, RUN, TODAY)

    def test_unhealthy_without_issue_creates(self):
        first = health(DANBOORU=BAD)
        actions = self.plan(first, first, [])
        self.assertEqual([("create", "DANBOORU")], [(a.kind, a.source) for a in actions])
        self.assertIn("provider-health-fingerprint", actions[0].body)

    def test_unconfirmed_failure_does_not_open_an_issue(self):
        self.assertEqual([], self.plan(health(DANBOORU=BAD), {}, []))

    def test_same_fingerprint_is_quiet(self):
        first = health(DANBOORU=BAD)
        self.assertEqual([], self.plan(first, first, [issue(7, "DANBOORU", first["DANBOORU"])]))

    def test_changed_fingerprint_edits_and_comments(self):
        old = health(DANBOORU=("DEGRADED", [("resolve-post", "DEGRADED", None)]))
        new = health(DANBOORU=BAD)
        actions = self.plan(new, new, [issue(7, "DANBOORU", old["DANBOORU"])])
        self.assertEqual([("update", 7)], [(a.kind, a.number) for a in actions])
        self.assertIn("newest-search", actions[0].body)
        self.assertTrue(actions[0].comment)

    def test_recovered_provider_closes_issue(self):
        first = health(DANBOORU=OK)
        actions = self.plan(first, {}, [issue(7, "DANBOORU", health(DANBOORU=BAD)["DANBOORU"])])
        self.assertEqual([("resolve", 7)], [(a.kind, a.number) for a in actions])

    def test_transient_blip_is_filtered(self):
        first, confirm = health(DANBOORU=BAD), health(DANBOORU=OK)
        self.assertEqual([], self.plan(first, confirm, []))
        closes = self.plan(first, confirm, [issue(7, "DANBOORU", first["DANBOORU"])])
        self.assertEqual("resolve", closes[0].kind)

    def test_skipped_and_absent_leave_issues_alone(self):
        existing = [issue(1, "PIXIV", health(PIXIV=BAD)["PIXIV"]), issue(2, "IWARA", health(IWARA=BAD)["IWARA"])]
        self.assertEqual([], self.plan(health(PIXIV=SKIP), {}, existing))
        # Confirm pass skipped it: no evidence it is still unhealthy or recovered.
        first = health(PIXIV=BAD)
        self.assertEqual([], self.plan(first, health(PIXIV=SKIP), []))

    def test_browser_challenge_is_not_retried_and_leaves_issues_alone(self):
        first = health(A=CHALLENGED)
        self.assertEqual(rph.retry_sources(first), [])
        self.assertEqual(self.plan(first, {}, []), [])
        self.assertEqual(self.plan(health(A=BAD), health(A=CHALLENGED), [issue(1, "A", body="x")]), [])

    def test_issues_are_matched_by_exact_title(self):
        first = health(DANBOORU=BAD)
        lookalike = {"number": 3, "title": "provider health: DANBOORU", "body": ""}
        self.assertEqual("create", self.plan(first, first, [lookalike])[0].kind)

    def test_retry_list_and_summary(self):
        first = health(AIBOORU=OK, DANBOORU=BAD, IWARA=("DEGRADED", []), PIXIV=SKIP)
        self.assertEqual(["DANBOORU", "IWARA"], rph.retry_sources(first))
        self.assertIn("| PIXIV | SKIPPED | - |", rph.render_summary(first, {}))


class SanitiseTest(unittest.TestCase):
    def test_check_names(self):
        self.assertEqual("gallery-animated-webp-range", rph.clean_check_name("gallery-4042375-animated-webp-range"))
        self.assertEqual("gg-configuration", rph.clean_check_name("gg-configuration"))
        self.assertEqual("unnamed-check", rph.clean_check_name("https://x.test/?k=1"))
        self.assertEqual("unnamed-check", rph.clean_check_name("1234"))
        self.assertEqual("unnamed-check", rph.clean_check_name(None))

    def test_null_reason_is_unclassified(self):
        self.assertEqual("UNCLASSIFIED", rph.clean_reason(None))

    def test_hostile_values_never_reach_output(self):
        hostile = "https://evil.test/p?api_key=SECRETKEY&user_id=99 <script>4042375</script> tag_text"
        data = {
            "liveProvidersEnabled": True,
            "results": [
                {"source": "DANBOORU", "checkName": hostile, "status": "FAILED", "failureReason": hostile,
                 "message": hostile, "requestUrl": hostile, "samplePostId": hostile, "itemCount": 4042375,
                 "autocompletePrefix": hostile, "includeTags": [hostile], "latencyMs": 4042375},
                {"source": hostile, "status": "FAILED"},
            ],
            "probeResults": [
                {"source": "DANBOORU", "checkName": hostile, "status": hostile, "failureReason": hostile,
                 "message": hostile, "requestUrl": hostile, "samplePostId": hostile, "itemCount": 4042375,
                 "autocompletePrefix": hostile, "includeTerms": [hostile]},
                {"source": "DANBOORU", "checkName": "gallery-4042375-resolve", "status": "DEGRADED",
                 "message": hostile},
            ],
        }
        first = rph.source_health(data)
        self.assertEqual(["DANBOORU"], list(first))
        old = rph.source_health(report(DANBOORU=("DEGRADED", [("resolve-post", "DEGRADED", None)])))
        issues = [issue(5, "DANBOORU", old["DANBOORU"])]
        rendered = [rph.render_summary(first, {})]
        for action in rph.plan(first, first, [], hostile, TODAY) + rph.plan(first, first, issues, hostile, TODAY):
            rendered += [action.title, action.body, action.comment]
        text = "\n".join(rendered)
        for needle in ("evil.test", "SECRETKEY", "4042375", "<script", "tag_text", "api_key", "user_id"):
            self.assertNotIn(needle, text)
        self.assertIn("gallery-resolve", text)


class FailClosedTest(unittest.TestCase):
    def load(self, content: str | None):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "r.json"
            if content is not None:
                path.write_text(content, encoding="utf-8")
            return rph.load_report(str(path))

    def test_rejects_untrustworthy_reports(self):
        for content in (None, "not json", "[]", '{"liveProvidersEnabled": false, "results": [{}]}',
                        '{"liveProvidersEnabled": true, "results": []}'):
            with self.subTest(content=content), self.assertRaises(rph.ReportError):
                self.load(content)

    def test_accepts_valid_report(self):
        self.assertIn("results", self.load(json.dumps(report(DANBOORU=OK))))


if __name__ == "__main__":
    unittest.main()
