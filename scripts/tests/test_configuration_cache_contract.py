import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE_PROPERTIES = REPOSITORY_ROOT / "gradle.properties"
CHECK_SCRIPT = REPOSITORY_ROOT / "scripts" / "check.sh"


class ConfigurationCacheContractTest(unittest.TestCase):
    def test_configuration_cache_is_enabled_in_strict_non_incubating_mode(self) -> None:
        properties = self._properties()

        self.assertEqual("true", properties.get("org.gradle.configuration-cache"))
        self.assertEqual("fail", properties.get("org.gradle.configuration-cache.problems"))
        self.assertNotIn("org.gradle.configuration-cache.parallel", properties)

    def test_check_proves_an_isolated_strict_entry_can_be_reused(self) -> None:
        check = CHECK_SCRIPT.read_text(encoding="utf-8")
        loop = check[check.index("for _ in store reuse; do"):check.index("# One invocation")]

        self.assertIn("set -euo pipefail", check)
        self.assertEqual(1, loop.count("./gradlew help"))
        self.assertIn("--configuration-cache-problems=fail", loop)
        self.assertIn('--project-cache-dir "$project_cache"', loop)
        self.assertIn("--console=plain", loop)
        self.assertIn('2>&1 | tee "$reuse_log"', loop)
        self.assertIn('reuse_signal="Configuration cache entry reused."', check)
        self.assertIn('grep -Fqx "$reuse_signal" "$reuse_log"', loop)
        self.assertIn("exit 1", loop)
        self.assertNotIn("--configuration-cache-problems=warn", check)

    def test_check_runs_custom_android_artifact_owners(self) -> None:
        check = CHECK_SCRIPT.read_text(encoding="utf-8")

        self.assertIn(":app:verifyBenchmarkFixtureArtifact", check)
        self.assertIn(":macrobenchmark:verifyMacrobenchmarkRunnerArtifact", check)
        # Release contracts run as assembleRelease finalizers.
        self.assertIn(":app:assembleRelease", check)

    def test_gradle_configuration_does_not_read_credentials_into_cache_inputs(self) -> None:
        forbidden_credential_environment_read = re.compile(
            r"(?:System\.getenv|providers\.environmentVariable)\s*\(\s*"
            r"[\"'][^\"']*(?:PIXIV|GELBOORU|RULE34|AUTH|TOKEN|PASSWORD|SECRET|API_KEY)",
            re.IGNORECASE,
        )
        forbidden_secret = re.compile(
            r"THEORIA_(?:PIXIV|GELBOORU|RULE34|AUTH|TOKEN|PASSWORD|SECRET|API_KEY)",
            re.IGNORECASE,
        )

        for path in self._gradle_configuration_files():
            contents = path.read_text(encoding="utf-8")
            relative = path.relative_to(REPOSITORY_ROOT)
            self.assertIsNone(
                forbidden_credential_environment_read.search(contents),
                f"{relative} must not make credential environment values configuration-cache inputs",
            )
            self.assertIsNone(
                forbidden_secret.search(contents),
                f"{relative} must not reference source credentials during configuration",
            )

    def _properties(self) -> dict[str, str]:
        result: dict[str, str] = {}
        for line in GRADLE_PROPERTIES.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            key, separator, value = stripped.partition("=")
            self.assertEqual("=", separator, f"Malformed Gradle property: {line}")
            self.assertNotIn(key, result, f"Duplicate Gradle property: {key}")
            result[key] = value
        return result

    def _gradle_configuration_files(self) -> list[pathlib.Path]:
        files = {
            REPOSITORY_ROOT / "gradle.properties",
            REPOSITORY_ROOT / "settings.gradle.kts",
            *REPOSITORY_ROOT.rglob("build.gradle.kts"),
        }
        return sorted(
            path
            for path in files
            if ".gradle" not in path.parts and "build" not in path.parts
        )


if __name__ == "__main__":
    unittest.main()
