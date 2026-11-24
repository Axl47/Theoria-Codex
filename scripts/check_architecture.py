#!/usr/bin/env python3
import functools
import re
import sys
from collections.abc import Callable
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

FORBIDDEN_APP_LOGIC_DEPENDENCIES = [
    'project(":app")',
    'project(":core-sources")',
    "com.android",
    "androidx.room",
    "androidx.media3",
    "androidx.compose",
    "coil",
]

FORBIDDEN_APP_LOGIC_IMPORTS = [
    "import android.",
    "import androidx.compose.",
    "import androidx.lifecycle.",
    "import androidx.room.",
    "import androidx.media3.",
    "import coil.",
    "import com.theoriacodex.sources.",
]

APP_LOGIC_OWNERS = [
    "SearchContract.kt",
    "SearchDraftReducer.kt",
    "SearchStateReducer.kt",
    "SearchExecutionContract.kt",
    "SearchScopedInputPolicy.kt",
    "SearchVisibilityFilters.kt",
    "FeedPlaybackPolicy.kt",
    "DurationFilterReadiness.kt",
    "PostMediaPolicy.kt",
    "TrainingTags.kt",
]

SCOPED_POLICY_DECLARATIONS = [
    "data class SearchScopePrefix",
    "data class ParsedScopedInput",
    "fun parseScopedInput",
    "fun resolveSupportedScope",
    "SEARCH_SCOPE_PREFIXES = mapOf",
    "const val UNIFIED_SCOPED_INPUT_BLOCKED_MESSAGE",
    "const val UNIFIED_SOURCE_TERMS_REMOVED_MESSAGE",
    "const val UNSUPPORTED_SEARCH_SCOPE_MESSAGE",
]


RULES: list[Callable[[], list[str]]] = []


def rule(check: Callable[[], list[str]]) -> Callable[[], list[str]]:
    RULES.append(check)
    return check


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def files_under(directory: Path):
    return (path for path in directory.rglob("*") if path.is_file())


@rule
def app_logic_stays_platform_free() -> list[str]:
    build = read("app-logic/build.gradle.kts")
    failures = [
        f"app-logic/build.gradle.kts must declare implementation(project(\":{module}\"))"
        for module in ("core-domain", "core-data")
        if f'implementation(project(":{module}"))' not in build
    ]
    failures += [
        f"app-logic/build.gradle.kts has a forbidden dependency: {forbidden}"
        for forbidden in FORBIDDEN_APP_LOGIC_DEPENDENCIES
        if forbidden in build
    ]
    for source in sorted((ROOT / "app-logic/src/main/kotlin").rglob("*.kt")):
        text = source.read_text(encoding="utf-8")
        failures += [
            f"{source.relative_to(ROOT)} has a forbidden '{forbidden}'"
            for forbidden in FORBIDDEN_APP_LOGIC_IMPORTS
            if forbidden in text
        ]
    pixiv_adapter = read("core-sources/src/main/kotlin/com/theoriacodex/sources/pixiv/PixivSourceAdapter.kt")
    if "com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME" not in pixiv_adapter:
        failures.append("PixivSourceAdapter's ugoira MIME symbol must delegate to com.theoriacodex.domain.model.PIXIV_UGOIRA_MIME")
    return failures


@rule
def moved_owners_stay_in_app_logic() -> list[str]:
    app_roots = [
        module / "src/main"
        for module in ROOT.iterdir()
        if module.is_dir() and (module.name.endswith("-kit") or module.name.startswith("feature-"))
    ] + [ROOT / "app/src/main/java"]
    app_names = {path.name for directory in app_roots for path in files_under(directory)}
    app_logic_names = {path.name for path in files_under(ROOT / "app-logic/src/main")}
    failures = []
    for owner in APP_LOGIC_OWNERS:
        if owner not in app_logic_names:
            failures.append(f"{owner} must exist in app-logic")
        if owner in app_names:
            failures.append(f"{owner} reappeared in :app, a kit or a feature; it belongs in app-logic")
    scoped_policy = read("app-logic/src/main/kotlin/com/theoriacodex/app/search/SearchScopedInputPolicy.kt")
    former_owners = [
        "feed-kit/src/main/kotlin/com/theoriacodex/app/search/SearchCoordinator.kt",
        "app-logic/src/main/kotlin/com/theoriacodex/app/search/state/SearchDraftReducer.kt",
    ]
    for declaration in SCOPED_POLICY_DECLARATIONS:
        if declaration not in scoped_policy:
            failures.append(f"SearchScopedInputPolicy.kt must own {declaration}")
        failures += [
            f"{Path(owner).name} duplicates {declaration}, which SearchScopedInputPolicy.kt owns"
            for owner in former_owners
            if declaration in read(owner)
        ]
    return failures


@rule
def quality_gates_cover_app_logic() -> list[str]:
    check = read("scripts/check.sh")
    boundaries = read("config/quality/coverage-boundaries.json")
    included_modules = boundaries.split('"includeModules": [', 1)[-1].split("]", 1)[0]
    expectations = [
        ('include(":app-logic")' in read("settings.gradle.kts"), "settings.gradle.kts must include :app-logic"),
        ('kover(project(":app-logic"))' in read("build.gradle.kts"), "build.gradle.kts must include :app-logic in coverage"),
        (":app-logic:detektMain" in check, "scripts/check.sh must run :app-logic:detektMain"),
        ("--boundaries config/quality/coverage-boundaries.json" in check, "scripts/check.sh must check coverage boundaries"),
        ('"app-logic"' in included_modules, "config/quality/coverage-boundaries.json must include app-logic"),
        ("app-logic/build/reports/detekt/" in read(".github/workflows/verify.yml"), "verify.yml must upload app-logic's Detekt report"),
    ]
    return [message for passed, message in expectations if not passed]


@rule
def delivery_plans_ignore_source_names() -> list[str]:
    post_media = read("media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt")
    plans = post_media.split("fun previewMediaDeliveryPlan", 1)[-1].split("private fun ImageRef.hasLighterRenditions", 1)[0]
    expectations = [
        ("hasLighterRenditions()" in plans.split("private fun mediaDeliveryPlan", 1)[-1],
         "PostMedia.kt's mediaDeliveryPlan must upgrade quality from hasLighterRenditions()"),
        ("SourceKey." not in plans, "PostMedia.kt's delivery plans must decide from the media, not from SourceKey"),
        (not re.search(r"setOf\(\s*SourceKey", post_media), "PostMedia.kt must not keep a set of SourceKeys"),
    ]
    return [message for passed, message in expectations if not passed]


NON_IDLING_WAIT_FILES = {
    "feature-viewer/src/test/kotlin/com/theoriacodex/app/viewer/ViewerUgoiraReadyTest.kt",
    "media-kit/src/test/kotlin/com/theoriacodex/app/ui/components/OffMainComputationTest.kt",
}

BARE_COMPOSE_WAIT = re.compile(r"(?<![A-Za-z0-9_])waitUntil\s*[({]")
WAIT_DECLARATION = re.compile(r"\bfun\s+waitUntil\s*\(")
NON_IDLING_WAIT = re.compile(r"\bwaitUntilWithoutIdling\b")


def is_bare_compose_wait(line: str) -> bool:
    return bool(BARE_COMPOSE_WAIT.search(line)) and not WAIT_DECLARATION.search(line)


@rule
def host_tests_wait_settled() -> list[str]:
    failures = []
    for source in sorted(ROOT.glob("*/src/test/**/*.kt")):
        relative = source.relative_to(ROOT).as_posix()
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
            if is_bare_compose_wait(line):
                failures.append(f"{relative}:{number} uses a bare waitUntil; use waitUntilSettled (docs/adr/0210-host-test-compose-waits.md)")
            if NON_IDLING_WAIT.search(line) and relative not in NON_IDLING_WAIT_FILES:
                failures.append(
                    f"{relative}:{number} uses waitUntilWithoutIdling, which only the screens listed in "
                    "docs/adr/0210-host-test-compose-waits.md and NON_IDLING_WAIT_FILES may use"
                )
    return failures


@functools.lru_cache(maxsize=None)
def kotlin_sources_under(root: Path) -> tuple[tuple[str, str], ...]:
    return tuple(
        (path.relative_to(root).as_posix(), path.read_text(encoding="utf-8"))
        for path in sorted(root.glob("*/src/main/**/*.kt"))
    )


def main_kotlin_sources() -> tuple[tuple[str, str], ...]:
    return kotlin_sources_under(ROOT)


OFF_MAIN_NOTE = re.compile(r"\bOffMainWork\.note\(([^)]*)\)")
OFF_MAIN_LABEL = re.compile(r"OffMainWork\.[A-Z][A-Z0-9_]*")
FUNCTION_OPENING = re.compile(r"\bfun\b.*\{$")


def off_main_note_failures(sources) -> list[str]:
    failures = []
    for relative, text in sources:
        lines = text.splitlines()
        for number, line in enumerate(lines, start=1):
            call = OFF_MAIN_NOTE.search(line)
            if not call:
                continue
            if not OFF_MAIN_LABEL.fullmatch(call.group(1).strip()):
                failures.append(f"{relative}:{number} notes '{call.group(1)}'; OffMainWork labels are constants (docs/adr/0002-coroutine-and-diagnostics-rules.md)")
            opening = next((previous.strip() for previous in reversed(lines[:number - 1]) if previous.strip()), "")
            if not FUNCTION_OPENING.search(opening) or re.search(r"\bsuspend\b", opening):
                failures.append(
                    f"{relative}:{number} must call OffMainWork.note first in the non-suspend function that does the work "
                    "(docs/adr/0002-coroutine-and-diagnostics-rules.md)"
                )
    return failures


@rule
def off_main_notes_run_where_work_runs() -> list[str]:
    return off_main_note_failures(main_kotlin_sources())


def references_outside(sources, symbol: re.Pattern, allowed: tuple[str, ...]) -> list[str]:
    return [relative for relative, text in sources if symbol.search(text) and not relative.startswith(allowed)]


RECOMMENDATION_NORMALIZATION = re.compile(r"\bRecommendationTagNormalization\b")
RECOMMENDATION_CODE = (
    "core-domain/src/main/kotlin/com/theoriacodex/domain/recommendation/",
    "app-logic/src/main/kotlin/com/theoriacodex/app/recommend/",
)


@rule
def recommendation_normalization_stays_in_recommendations() -> list[str]:
    return [
        f"{relative} uses RecommendationTagNormalization, which only recommendation code may use "
        "(docs/adr/0003-cross-source-tags.md)"
        for relative in references_outside(main_kotlin_sources(), RECOMMENDATION_NORMALIZATION, RECOMMENDATION_CODE)
    ]


DERIVED_SITE_ID = re.compile(r"\bderivedSourceId\b|\bfromAddress\(")
SITE_ID_NAMERS = (
    "core-domain/src/main/kotlin/com/theoriacodex/domain/source/CustomSites.kt",
    "app/src/main/java/com/theoriacodex/app/di/CustomSitesControllerImpl.kt",
    "core-sources/src/main/kotlin/com/theoriacodex/sources/custom/Families.kt",
)


@rule
def stored_site_ids_are_never_rederived() -> list[str]:
    return [
        f"{relative} derives a custom site id from its address; a stored profile's id is authoritative "
        "(docs/adr/0004-custom-site-profiles.md)"
        for relative in references_outside(main_kotlin_sources(), DERIVED_SITE_ID, SITE_ID_NAMERS)
    ]


FILE_HASH_LOOKUP_SUPERTYPE = re.compile(r"(?:\s:|,)\s*FileHashLookupSourceAdapter\b")
MD5_SEARCH_FAMILIES = (
    "core-sources/src/main/kotlin/com/theoriacodex/sources/danbooru/",
    "core-sources/src/main/kotlin/com/theoriacodex/sources/gelbooru/",
)


@rule
def only_md5_families_look_up_file_hashes() -> list[str]:
    return [
        f"{relative} implements FileHashLookupSourceAdapter, which only families with an md5: search may "
        "(docs/adr/0008-source-adapter-contracts.md)"
        for relative in references_outside(main_kotlin_sources(), FILE_HASH_LOOKUP_SUPERTYPE, MD5_SEARCH_FAMILIES)
    ]


MIN_SCORE_READ = re.compile(r"\.minScore\b")
MIN_SCORE_KEEPERS = (
    "core-domain/src/main/kotlin/com/theoriacodex/domain/query/QueryHash.kt",
    "core-data/src/main/kotlin/com/theoriacodex/data/storage/RecentsStorage.kt",
    "feature-search/src/main/kotlin/com/theoriacodex/app/search/SearchSavedState.kt",
)


@rule
def sources_ignore_min_score() -> list[str]:
    return [
        f"{relative} reads Query.minScore, a retired filter only stores and the query hash may read "
        "(docs/adr/0009-stored-data-compatibility.md)"
        for relative in references_outside(main_kotlin_sources(), MIN_SCORE_READ, MIN_SCORE_KEEPERS)
    ]


def main() -> int:
    failures = [failure for rule in RULES for failure in rule()]
    for failure in failures:
        print(failure, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
