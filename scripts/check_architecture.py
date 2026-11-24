#!/usr/bin/env python3
import re
import sys
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


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def files_under(directory: Path):
    return (path for path in directory.rglob("*") if path.is_file())


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


def host_tests_wait_settled() -> list[str]:
    failures = []
    for source in sorted(ROOT.glob("*/src/test/**/*.kt")):
        relative = source.relative_to(ROOT).as_posix()
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
            if is_bare_compose_wait(line):
                failures.append(f"{relative}:{number} uses a bare waitUntil; use waitUntilSettled (docs/adr/0040-host-test-compose-waits.md)")
            if NON_IDLING_WAIT.search(line) and relative not in NON_IDLING_WAIT_FILES:
                failures.append(
                    f"{relative}:{number} uses waitUntilWithoutIdling, which only the screens listed in "
                    "docs/adr/0040-host-test-compose-waits.md and NON_IDLING_WAIT_FILES may use"
                )
    return failures


def main() -> int:
    failures = (
        app_logic_stays_platform_free()
        + moved_owners_stay_in_app_logic()
        + quality_gates_cover_app_logic()
        + delivery_plans_ignore_source_names()
        + host_tests_wait_settled()
    )
    for failure in failures:
        print(failure, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
