import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE_PROPERTIES = REPOSITORY_ROOT / "gradle.properties"
VERIFY_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "verify.yml"


class ConfigurationCacheContractTest(unittest.TestCase):
    def test_configuration_cache_is_enabled_in_strict_non_incubating_mode(self) -> None:
        properties = self._properties()

        self.assertEqual("true", properties.get("org.gradle.configuration-cache"))
        self.assertEqual("fail", properties.get("org.gradle.configuration-cache.problems"))
        self.assertNotIn("org.gradle.configuration-cache.parallel", properties)

    def test_ci_proves_an_isolated_strict_entry_can_be_reused(self) -> None:
        workflow = VERIFY_WORKFLOW.read_text(encoding="utf-8")
        step_start = workflow.index("- name: Verify strict configuration-cache store and reuse")
        step_end = workflow.index("\n      - name:", step_start + 1)
        step = workflow[step_start:step_end]

        self.assertEqual(2, step.count("./gradlew help"))
        self.assertEqual(2, step.count("--configuration-cache-problems=fail"))
        self.assertEqual(2, step.count('--project-cache-dir "$project_cache"'))
        self.assertEqual(2, step.count("--console=plain"))
        self.assertIn("set -euo pipefail", step)
        self.assertIn('reuse_log="$RUNNER_TEMP/theoria-config-cache-reuse.log"', step)
        self.assertIn('reuse_signal="Configuration cache entry reused."', step)
        self.assertIn('2>&1 | tee "$reuse_log"', step)
        self.assertIn('grep -Fqx "$reuse_signal" "$reuse_log"', step)
        self.assertIn("exit 1", step)
        self.assertNotIn("--configuration-cache-problems=warn", workflow)

    def test_ci_strictly_configures_custom_android_artifact_owners(self) -> None:
        workflow = VERIFY_WORKFLOW.read_text(encoding="utf-8")
        step_start = workflow.index(
            "- name: Verify custom Android artifact tasks are configuration-cache compatible",
        )
        step_end = workflow.index("\n      - name:", step_start + 1)
        step = workflow[step_start:step_end]

        self.assertIn(":app:verifyBenchmarkFixtureArtifact", step)
        self.assertIn(":app:verifyReleaseJsonContracts", step)
        self.assertIn(":app:verifyReleaseAcceptanceJsonContracts", step)
        self.assertIn(":macrobenchmark:verifyMacrobenchmarkRunnerArtifact", step)
        self.assertIn("--configuration-cache", step)
        self.assertIn("--configuration-cache-problems=fail", step)

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
