from __future__ import annotations

import contextlib
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import check_architecture  # noqa: E402


class BareComposeWaitTest(unittest.TestCase):
    def test_flags_every_spelling_of_a_bare_wait_adr_0210(self) -> None:
        spellings = [
            "compose.waitUntil(10_000) { ready }",
            "        waitUntil { ready }",
            "with(compose) { waitUntil { ready } }",
            "val done = waitUntil(timeoutMillis = 1_000) { ready }",
            "runComposeUiTest { waitUntil { ready } }",
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "feature/src/test/kotlin"
            tests.mkdir(parents=True)
            (tests / "BareTest.kt").write_text("\n".join(spellings) + "\n", encoding="utf-8")
            (tests / "SettledTest.kt").write_text("compose.waitUntilSettled { ready }\n", encoding="utf-8")
            self.assertIn(check_architecture.host_tests_wait_settled, check_architecture.RULES)
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    [f"feature/src/test/kotlin/BareTest.kt:{number} uses a bare waitUntil; use waitUntilSettled (docs/adr/0210-host-test-compose-waits.md)"
                     for number in range(1, len(spellings) + 1)],
                    check_architecture.host_tests_wait_settled(),
                )

    def test_allows_the_settled_helpers_and_declarations(self) -> None:
        for line in [
            "compose.waitUntilSettled { ready }",
            "compose.waitUntilWithoutIdling(5_000) { parked }",
            "compose.waitUntilAtLeastOneExists(hasText(\"A\"))",
            "private fun waitUntil(condition: () -> Boolean) = Unit",
        ]:
            self.assertFalse(check_architecture.is_bare_compose_wait(line), line)


class DeliveryPlanTest(unittest.TestCase):
    def test_reports_a_delivery_plan_that_decides_from_a_source_adr_0074(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            post_media = root / "media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt"
            post_media.parent.mkdir(parents=True)
            post_media.write_text(
                "fun previewMediaDeliveryPlan() = Unit\n"
                "private fun mediaDeliveryPlan(post: Post) = post.source == SourceKey.Pixiv || hasLighterRenditions()\n"
                "private fun ImageRef.hasLighterRenditions() = true\n",
                encoding="utf-8",
            )
            self.assertIn(check_architecture.delivery_plans_ignore_source_names, check_architecture.RULES)
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    ["PostMedia.kt's delivery plans must decide from the media, not from SourceKey"],
                    check_architecture.delivery_plans_ignore_source_names(),
                )


@contextlib.contextmanager
def main_sources(files: dict[str, str]):
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for relative, text in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        with mock.patch.object(check_architecture, "ROOT", root):
            yield


class CoreDomainRulesTest(unittest.TestCase):
    def test_reports_off_main_notes_outside_the_start_of_a_parse_helper_adr_0002(self) -> None:
        self.assertIn(check_architecture.off_main_notes_run_where_work_runs, check_architecture.RULES)
        with main_sources({
            "core-sources/src/main/kotlin/Clean.kt": (
                "internal fun parseHtml(html: String): Document {\n"
                "    OffMainWork.note(OffMainWork.SOURCE_PARSE)\n"
                "    return Jsoup.parse(html)\n"
                "}\n"
                "private fun SourceHttpResponse.jsonBody() = body.takeIf(String::isNotBlank)?.let {\n"
                "    OffMainWork.note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
            ),
            "core-sources/src/main/kotlin/Broken.kt": (
                "override suspend fun search(query: Query): Page {\n"
                "    OffMainWork.note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
                "fun parse(html: String): Document {\n"
                "    val trimmed = html.trim()\n"
                "    OffMainWork.note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
                "fun title(post: Post): Document {\n"
                "    OffMainWork.note(post.title)\n"
                "}\n"
            ),
        }):
            failures = check_architecture.off_main_notes_run_where_work_runs()
        self.assertEqual(
            ["core-sources/src/main/kotlin/Broken.kt:2", "core-sources/src/main/kotlin/Broken.kt:6", "core-sources/src/main/kotlin/Broken.kt:9"],
            [failure.split(" ", 1)[0] for failure in failures],
        )

    def test_reports_recommendation_normalization_outside_recommendation_code_adr_0003(self) -> None:
        self.assertIn(check_architecture.recommendation_normalization_stays_in_recommendations, check_architecture.RULES)
        use = "val key = RecommendationTagNormalization.normalize(source, tag)\n"
        with main_sources({
            "app-logic/src/main/kotlin/com/theoriacodex/app/recommend/ForYouInterests.kt": use,
            "feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinator.kt": use,
        }):
            failures = check_architecture.recommendation_normalization_stays_in_recommendations()
        self.assertEqual(["feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinator.kt"], [failure.split(" ", 1)[0] for failure in failures])

    def test_reports_a_custom_site_id_derived_outside_profile_creation_adr_0004(self) -> None:
        self.assertIn(check_architecture.stored_site_ids_are_never_rederived, check_architecture.RULES)
        with main_sources({
            "app/src/main/java/com/theoriacodex/app/di/CustomSitesControllerImpl.kt": "sourceId = address.derivedSourceId,\n",
            "app/src/main/java/com/theoriacodex/app/di/CustomSourceRuntime.kt": "val id = CustomSourceIds.fromAddress(host, port, path)\n",
        }):
            failures = check_architecture.stored_site_ids_are_never_rederived()
        self.assertEqual(["app/src/main/java/com/theoriacodex/app/di/CustomSourceRuntime.kt"], [failure.split(" ", 1)[0] for failure in failures])

    def test_reports_a_file_hash_lookup_outside_md5_search_families_adr_0008(self) -> None:
        self.assertIn(check_architecture.only_md5_families_look_up_file_hashes, check_architecture.RULES)
        with main_sources({
            "core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/DanbooruSourceAdapter.kt": "class D(x: Int) : SourceAdapter,\n    FileHashLookupSourceAdapter {\n",
            "core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt": "class P : SourceAdapter, FileHashLookupSourceAdapter {\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/source/FileCopyLookup.kt": "fun f(lookup: FileHashLookupSourceAdapter) = adapter as? FileHashLookupSourceAdapter\n",
        }):
            failures = check_architecture.only_md5_families_look_up_file_hashes()
        self.assertEqual(["core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt"], [failure.split(" ", 1)[0] for failure in failures])

    def test_reports_a_min_score_read_outside_stores_and_the_hash_adr_0009(self) -> None:
        self.assertIn(check_architecture.sources_ignore_min_score, check_architecture.RULES)
        with main_sources({
            "core-domain/src/main/kotlin/com/theoriacodex/domain/query/QueryHash.kt": "val score = query.minScore?.toString()\n",
            "core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapter.kt": "Query(sort = sort, minScore = null)\nval floor = query.minScore ?: 0\n",
            "feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinatorPolicy.kt": "Query(sort = sort, minScore = null)\n",
        }):
            failures = check_architecture.sources_ignore_min_score()
        self.assertEqual(["core-sources/src/main/kotlin/com/theoriacodex/sources/nhentai/NhentaiSourceAdapter.kt"], [failure.split(" ", 1)[0] for failure in failures])


if __name__ == "__main__":
    unittest.main()
