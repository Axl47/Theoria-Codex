import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
APP_BUILD = REPOSITORY_ROOT / "app" / "build.gradle.kts"
CHECK_SCRIPT = REPOSITORY_ROOT / "scripts" / "check.sh"
WORKFLOWS = REPOSITORY_ROOT / ".github" / "workflows"
RELEASE_WORKFLOW = WORKFLOWS / "release.yml"


class R8JsonContractBuildTest(unittest.TestCase):
    def test_release_verifiers_consume_the_public_agp_mapping_artifact(self) -> None:
        build = APP_BUILD.read_text(encoding="utf-8")

        self.assertIn("SingleArtifact.OBFUSCATION_MAPPING_FILE", build)
        self.assertIn("variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)", build)
        self.assertNotIn('outputs/mapping/$variantName/mapping.txt', build)
        self.assertNotIn("intermediates/mapping", build)
        self.assertNotIn('dependsOn("minify', build)

    def test_release_verifiers_declare_matching_seeds_as_an_explicit_input(self) -> None:
        build = APP_BUILD.read_text(encoding="utf-8")
        task_start = build.index("abstract class VerifyR8JsonContractsTask")
        task_end = build.index("\nplugins {", task_start)
        task = build[task_start:task_end]

        self.assertIn("abstract val mappingFile: RegularFileProperty", task)
        self.assertIn("abstract val seedsFile: RegularFileProperty", task)
        self.assertIn("mappingFile.get().asFile", task)
        self.assertIn("seedsFile.get().asFile", task)
        self.assertIn('outputs/mapping/${variant.name}/seeds.txt', build)
        self.assertNotIn("doFirst", build)

    def test_both_public_verifiers_are_variant_owned_and_assembly_finalized(self) -> None:
        build = APP_BUILD.read_text(encoding="utf-8")

        declaration = re.search(r"setOf\(([^)]*)\)\.forEach \{ buildType ->", build)
        self.assertIsNotNone(declaration)
        variants = set(re.findall(r'"([^"]+)"', declaration.group(1)))
        self.assertTrue({"release", "releaseAcceptance", "benchmarkRelease"}.issubset(variants))
        self.assertIn('"verify${capitalizedVariant}JsonContracts"', build)
        self.assertIn('task.name == "assemble$capitalizedVariant"', build)
        self.assertIn("finalizedBy(verification)", build)

    def test_check_and_release_use_public_gradle_owners(self) -> None:
        check = CHECK_SCRIPT.read_text(encoding="utf-8")
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")

        device_validation = (WORKFLOWS / "device-validation.yml").read_text(encoding="utf-8")

        # Both contract verifiers finalize their assembly task, so CI runs them through it.
        self.assertIn(":app:assembleRelease", check)
        self.assertIn(":app:assembleReleaseAcceptance", device_validation)
        self.assertIn(":app:assembleRelease", release)
        self.assertNotIn("verify_r8_json_contract.py", release)


if __name__ == "__main__":
    unittest.main()
