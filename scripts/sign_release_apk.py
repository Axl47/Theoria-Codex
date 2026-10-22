#!/usr/bin/env python3
"""Sign one APK with the existing CI key using the Android SDK, then verify it."""

from __future__ import annotations

import argparse
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def sign_apk(source: Path, destination: Path) -> None:
    source = source.resolve(strict=True)
    destination = destination.resolve()
    if source == destination or destination.exists():
        raise ValueError("Signing requires a new destination, separate from the unsigned APK")
    sdk = Path(os.environ["ANDROID_HOME"])
    signer = sdk / "build-tools" / os.environ.get("BUILD_TOOLS_VERSION", "36.0.0") / "apksigner"
    if not signer.is_file():
        raise FileNotFoundError(f"Android SDK signer is missing: {signer}")
    keystore = base64.b64decode(os.environ["THEORIA_UPLOAD_KEYSTORE_BASE64"], validate=True)
    alias = os.environ["THEORIA_UPLOAD_KEY_ALIAS"]
    if not keystore or not alias or not os.environ.get("THEORIA_UPLOAD_STORE_PASSWORD"):
        raise ValueError("The signing keystore, alias, and store password are required")

    # Keep decoded signing material outside the APK/artifact directories and remove it on failure too.
    with tempfile.TemporaryDirectory(prefix="theoria-signing-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        key_path = Path(temporary) / "release.jks"
        key_path.write_bytes(keystore)
        key_path.chmod(0o600)
        signed_apk = Path(temporary) / "signed.apk"
        command = [str(signer), "sign", "--ks", str(key_path), "--ks-key-alias", alias,
                   "--ks-pass", "env:THEORIA_UPLOAD_STORE_PASSWORD", "--v4-signing-enabled", "false",
                   "--out", str(signed_apk)]
        if os.environ.get("THEORIA_UPLOAD_KEY_PASSWORD"):
            command += ["--key-pass", "env:THEORIA_UPLOAD_KEY_PASSWORD"]
        subprocess.run([*command, str(source)], check=True)
        subprocess.run([str(signer), "verify", "--verbose", str(signed_apk)], check=True)
        destination.parent.mkdir(parents=True, exist_ok=True)
        # Publish the artifact only after verification succeeds.
        with signed_apk.open("rb") as source_file, destination.open("xb") as output:
            shutil.copyfileobj(source_file, output)

    if github_output := os.environ.get("GITHUB_OUTPUT"):
        with Path(github_output).open("a", encoding="utf-8") as output:
            output.write(f"signedReleaseFile={destination}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    sign_apk(arguments.source, arguments.destination)


if __name__ == "__main__":
    main()
