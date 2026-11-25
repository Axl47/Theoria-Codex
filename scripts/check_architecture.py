#!/usr/bin/env python3
import ipaddress
import re
import sys
import xml.etree.ElementTree as ElementTree
from collections.abc import Callable, Iterable
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


PIXIV_MEDIUM_FIRST_EXCEPTION = "if (post.id.source == SourceKey.PIXIV && full != null) {"
SOURCE_DECISION = re.compile(r"source\.id\b|isCustom|source\s*[!=]=")


@rule
def delivery_plans_ignore_source_names() -> list[str]:
    post_media = read("media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt")
    plans = post_media.split("fun previewMediaDeliveryPlan", 1)[-1].split("private fun ImageRef.hasLighterRenditions", 1)[0]
    candidate = post_media.split("fun postPreviewImageCandidate", 1)[-1].split("fun postPlaybackMediaCandidate", 1)[0]
    decided = candidate.replace(PIXIV_MEDIUM_FIRST_EXCEPTION, "") + plans
    expectations = [
        ("hasLighterRenditions()" in plans.split("private fun mediaDeliveryPlan", 1)[-1],
         "PostMedia.kt's mediaDeliveryPlan must upgrade quality from hasLighterRenditions()"),
        ("SourceKey." not in decided, "PostMedia.kt's delivery plans must decide from the media, not from SourceKey"),
        (not SOURCE_DECISION.search(decided),
         "PostMedia.kt's delivery plans must not decide from a source id or isCustom"),
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


OFF_MAIN_CALL = re.compile(r"(?:(?<![\w.])(?:[a-z]\w*\s*\.\s*)+)?\bOffMainWork\s*\.\s*note\s*\(")
BARE_NOTE_IMPORT = re.compile(r"^import\s+com\.theoriacodex\.domain\.diagnostics\.OffMainWork\.note\s*$", re.MULTILINE)
BARE_NOTE_CALL = re.compile(r"(?<![\w.])(?<!fun\s)note\s*\(")
OFF_MAIN_LABEL = re.compile(r"(?:label\s*=\s*)?(?:[A-Za-z_]\w*\.)*OffMainWork\.[A-Z][A-Z0-9_]*")
KOTLIN_LITERAL = re.compile(r"'(?:\\.|[^'\\\n])'|\"\"\"[\s\S]*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"")
FUNCTION_KEYWORD = re.compile(r"\bfun\b")
DECLARATION_MODIFIERS = re.compile(
    r"(?:(?:@[\w.]+(?:\([^)]*\))?|public|private|internal|protected|override|open|final|inline|suspend|tailrec|operator|infix)\s+)*\Z"
)


def closing_paren(text: str, opening: int) -> int:
    depth = 0
    for index in range(opening, len(text)):
        depth += {"(": 1, ")": -1}.get(text[index], 0)
        if depth == 0:
            return index
    return len(text)


def opening_brace(text: str, before: int) -> int:
    depth = 0
    for index in range(before - 1, -1, -1):
        if text[index] == "}":
            depth += 1
        elif text[index] == "{":
            if depth == 0:
                return index
            depth -= 1
    return -1


def notes_in_a_suspend_or_late_spot(text: str, call: int) -> bool:
    brace = opening_brace(text, call)
    if brace < 0 or text[brace + 1:call].strip():
        return True
    keywords = list(FUNCTION_KEYWORD.finditer(text, 0, brace))
    if not keywords:
        return True
    header = text[keywords[-1].end():brace]
    if header.count("{") != header.count("}"):
        return True
    modifiers = DECLARATION_MODIFIERS.search(text, 0, keywords[-1].start())
    return modifiers is not None and re.search(r"\bsuspend\b", modifiers.group(0)) is not None


def off_main_note_failures(sources: Iterable[tuple[str, str]]) -> list[str]:
    failures = []
    for relative, source in sources:
        text = KOTLIN_LITERAL.sub(lambda literal: re.sub(r".", " ", literal.group(0)), source)
        calls = list(OFF_MAIN_CALL.finditer(text))
        if BARE_NOTE_IMPORT.search(text):
            calls += BARE_NOTE_CALL.finditer(text)
        for call in sorted(calls, key=lambda match: match.start()):
            number = text.count("\n", 0, call.start()) + 1
            label = " ".join(text[call.end():closing_paren(text, call.end() - 1)].split()).rstrip(",").strip()
            if not OFF_MAIN_LABEL.fullmatch(label):
                failures.append(f"{relative}:{number} notes '{label}'; OffMainWork labels are constants (docs/adr/0002-coroutine-and-diagnostics-rules.md)")
            if notes_in_a_suspend_or_late_spot(text, call.start()):
                failures.append(
                    f"{relative}:{number} must call OffMainWork.note first in the non-suspend function that does the work "
                    "(docs/adr/0002-coroutine-and-diagnostics-rules.md)"
                )
    return failures


@rule
def off_main_notes_run_where_work_runs() -> list[str]:
    return off_main_note_failures(
        (path.relative_to(ROOT).as_posix(), path.read_text(encoding="utf-8")) for path in sorted(ROOT.glob("*/src/main/**/*.kt"))
    )


READ_LOGS_REQUEST = re.compile(r"<uses-permission\b[^>]*>")
XML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
REMOVED_BY_MERGER = re.compile(r"""tools:node\s*=\s*["']remove["']""")


@rule
def no_manifest_reads_other_apps_logs() -> list[str]:
    return [
        f"{path.relative_to(ROOT).as_posix()} requests READ_LOGS; the diagnostic report reads only the app's own log "
        "(docs/adr/0103-diagnostics-log-and-usage.md)"
        for path in sorted(ROOT.glob("*/src/*/AndroidManifest.xml"))
        if any(
            "android.permission.READ_LOGS" in tag and not REMOVED_BY_MERGER.search(tag)
            for tag in READ_LOGS_REQUEST.findall(XML_COMMENT.sub("", path.read_text(encoding="utf-8")))
        )
    ]


DOWNLOAD_MANAGER = re.compile(r"\bDownloadManager\b|\bDOWNLOAD_SERVICE\b")
DOWNLOAD_MANAGER_OWNERS = re.compile(r"media-kit/src/main/kotlin/(?:.+/)?(?:PostDownloads|PostDownloadService|PostDownloadFiles)\.kt")
STRINGS_AND_COMMENTS = re.compile(r"'(?:\\.|[^'\\\n])'|" r'""".*?"""|"(?:\\.|[^"\\\n])*"|//[^\n]*|/\*.*?\*/', re.DOTALL)
TEST_SOURCE_SETS = ("test", "androidTest", "sharedTest")


def shipping_sources() -> Iterable[Path]:
    return sorted(
        path for pattern in ("*/src/*/**/*.kt", "*/src/*/**/*.java") for path in ROOT.glob(pattern)
        if not path.relative_to(ROOT).parts[2].startswith(TEST_SOURCE_SETS)
    )


@rule
def only_post_downloads_use_download_manager() -> list[str]:
    return [
        f"{relative} uses Android's DownloadManager; only post downloads queue there, and animation export uses the "
        "app's own transport (docs/adr/0110-offline-media-jobs.md)"
        for path in shipping_sources()
        if not DOWNLOAD_MANAGER_OWNERS.fullmatch(relative := path.relative_to(ROOT).as_posix())
        and DOWNLOAD_MANAGER.search(STRINGS_AND_COMMENTS.sub("", path.read_text(encoding="utf-8")))
    ]


ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"
TOOLS_NAMESPACE = "{http://schemas.android.com/tools}"
CLEARTEXT_ADR = "(docs/adr/0048-provider-media-urls.md)"


@rule
def provider_media_never_loads_in_cleartext() -> list[str]:
    failures = []
    for manifest_path in shipped_files("*/src/*/AndroidManifest.xml"):
        name = manifest_path.relative_to(ROOT).as_posix()
        application = ElementTree.fromstring(manifest_path.read_text(encoding="utf-8")).find("application")
        if application is None:
            continue
        config_name = application.get(f"{ANDROID_NAMESPACE}networkSecurityConfig")
        if config_name not in (None, "@xml/network_security_config") or (config_name is None and name == "app/src/main/AndroidManifest.xml"):
            failures.append(f"{name} must apply @xml/network_security_config {CLEARTEXT_ADR}")
        if application.get(f"{ANDROID_NAMESPACE}usesCleartextTraffic", "false") != "false":
            failures.append(f"{name} must not set usesCleartextTraffic {CLEARTEXT_ADR}")
        removed = application.get(f"{TOOLS_NAMESPACE}remove", "")
        if application.get(f"{TOOLS_NAMESPACE}node") in ("replace", "remove") or "networkSecurityConfig" in removed:
            failures.append(f"{name} must not drop the application's network security config {CLEARTEXT_ADR}")
    configs = shipped_files("*/src/*/res/xml*/network_security_config.xml")
    if not any(path.relative_to(ROOT).parts[0] == "app" for path in configs):
        failures.append(f"app has no network_security_config.xml {CLEARTEXT_ADR}")
    for config_path in configs:
        name = config_path.relative_to(ROOT).as_posix()
        config = ElementTree.fromstring(config_path.read_text(encoding="utf-8"))
        for element in config.iter():
            value = element.get("cleartextTrafficPermitted")
            if value not in (None, "true", "false"):
                failures.append(f'{name} sets cleartextTrafficPermitted="{value}"; write true or false {CLEARTEXT_ADR}')
        base = config.find("base-config")
        if base is None or base.get("cleartextTrafficPermitted") != "false":
            failures.append(f"{name} must refuse cleartext in its base-config {CLEARTEXT_ADR}")
        base_permits = base is None or base.get("cleartextTrafficPermitted", "true") != "false"
        for host in sorted(host for host in cleartext_hosts(config, base_permits) if not is_loopback(host)):
            failures.append(f"{name} allows cleartext to {host}; only loopback may {CLEARTEXT_ADR}")
    return failures


def shipped_files(pattern: str) -> list[Path]:
    return [path for path in sorted(ROOT.glob(pattern)) if not path.relative_to(ROOT).parts[2].startswith(("test", "androidTest"))]


def is_loopback(host: str) -> bool:
    name = host.strip().strip("[]").lower()
    if name == "localhost":
        return True
    try:
        return ipaddress.ip_address(name).is_loopback
    except ValueError:
        return False


def cleartext_hosts(parent: ElementTree.Element, inherited: bool) -> set[str]:
    hosts: set[str] = set()
    for domain_config in parent.findall("domain-config"):
        permitted = domain_config.get("cleartextTrafficPermitted", "true" if inherited else "false") != "false"
        if permitted:
            hosts |= {(domain.text or "").strip() for domain in domain_config.findall("domain")}
        hosts |= cleartext_hosts(domain_config, permitted)
    return hosts



OKHTTP_CLIENT_CONSTRUCTION = re.compile(r"\bOkHttpClient\s*(?:\.\s*Builder\s*)?\(\s*\)")
OKHTTP_CLIENT_OWNERS = (
    "core-sources/src/main/kotlin/com/theoriacodex/sources/http/DefaultSourceHttpClient.kt",
    "extension-host/src/main/kotlin/com/theoriacodex/extensionhost/ExtensionHost.kt",
    "media-kit/src/main/kotlin/com/theoriacodex/app/network/AppHttpClients.kt",
    "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraClient.kt",
    "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoHttpDataSource.kt",
    "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoPlaybackInfrastructure.kt",
)


@rule
def http_clients_derive_from_the_shared_client() -> list[str]:
    return [
        f"{relative} builds its own OkHttpClient; derive it from AppHttpClients' shared client with newBuilder() "
        "(docs/adr/0102-app-http-clients.md)"
        for path in shipping_sources()
        if (relative := path.relative_to(ROOT).as_posix()) not in OKHTTP_CLIENT_OWNERS
        and OKHTTP_CLIENT_CONSTRUCTION.search(STRINGS_AND_COMMENTS.sub("", path.read_text(encoding="utf-8")))
    ]

def main() -> int:
    failures = [failure for rule in RULES for failure in rule()]
    for failure in failures:
        print(failure, file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
