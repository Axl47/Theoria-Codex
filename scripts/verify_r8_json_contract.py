#!/usr/bin/env python3
"""Fail when R8 removes or renames a durable Gson field.

SerializedName is the primary wire-name contract. The app's exact keep rules additionally retain
the backing field names so mapping.txt is an auditable release artifact rather than a false-positive
build. This verifier intentionally knows nothing about packages beyond the explicit manifest.
"""

from __future__ import annotations

import pathlib
import sys


def read_contracts(path: pathlib.Path) -> dict[str, tuple[str, ...]]:
    contracts: dict[str, tuple[str, ...]] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "|" not in line:
            raise ValueError(f"{path}:{line_number}: malformed contract line")
        class_name, raw_fields = line.split("|", 1)
        fields = tuple(field.strip() for field in raw_fields.split(",") if field.strip())
        if not class_name or not fields or class_name in contracts:
            raise ValueError(f"{path}:{line_number}: invalid or duplicate contract")
        contracts[class_name] = fields
    return contracts


def read_mapping(path: pathlib.Path) -> dict[str, dict[str, str]]:
    classes: dict[str, dict[str, str]] = {}
    current_fields: dict[str, str] | None = None
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if raw_line and not raw_line[0].isspace() and raw_line.endswith(":") and " -> " in raw_line:
            original_name = raw_line.split(" -> ", 1)[0]
            current_fields = classes.setdefault(original_name, {})
            continue
        if current_fields is None or " -> " not in raw_line:
            continue
        left, renamed = raw_line.strip().rsplit(" -> ", 1)
        if "(" in left or ")" in left:
            continue
        original_field = left.rsplit(" ", 1)[-1]
        current_fields[original_field] = renamed
    return classes


def read_seeds(path: pathlib.Path) -> tuple[dict[str, set[str]], set[str]]:
    fields: dict[str, set[str]] = {}
    no_arg_constructors: set[str] = set()
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if ": " not in raw_line:
            continue
        class_name, member = raw_line.split(": ", 1)
        simple_name = class_name.rsplit(".", 1)[-1]
        if member == f"{simple_name}()":
            no_arg_constructors.add(class_name)
            continue
        if "(" in member or " " not in member:
            continue
        fields.setdefault(class_name, set()).add(member.rsplit(" ", 1)[-1])
    return fields, no_arg_constructors


def main() -> int:
    if len(sys.argv) not in (2, 3, 4):
        print(
            "usage: verify_r8_json_contract.py MAPPING [CONTRACT_MANIFEST] [SEEDS]",
            file=sys.stderr,
        )
        return 2

    mapping_path = pathlib.Path(sys.argv[1])
    manifest_path = (
        pathlib.Path(sys.argv[2])
        if len(sys.argv) >= 3
        else pathlib.Path("app/src/test/resources/r8-json-contracts.txt")
    )
    seeds_path = pathlib.Path(sys.argv[3]) if len(sys.argv) == 4 else mapping_path.with_name("seeds.txt")
    if not mapping_path.is_file():
        print(f"R8 mapping is missing: {mapping_path}", file=sys.stderr)
        return 1
    if not manifest_path.is_file():
        print(f"JSON contract manifest is missing: {manifest_path}", file=sys.stderr)
        return 1
    if not seeds_path.is_file():
        print(f"R8 seeds are missing: {seeds_path}", file=sys.stderr)
        return 1

    contracts = read_contracts(manifest_path)
    mapping = read_mapping(mapping_path)
    seeded_fields, seeded_no_arg_constructors = read_seeds(seeds_path)
    failures: list[str] = []
    removed_classes: list[str] = []
    checked_fields = 0
    for class_name, expected_fields in contracts.items():
        retained_fields = seeded_fields.get(class_name)
        mapped_fields = mapping.get(class_name)
        if retained_fields is None and mapped_fields is None:
            # R8 can remove a DTO whose entire storage family is no longer reachable in the
            # shipping graph. That is safe: if a production path references the type again, the
            # class reappears and every reflected field below becomes mandatory.
            removed_classes.append(class_name)
            continue
        if class_name not in seeded_no_arg_constructors:
            failures.append(f"{class_name}: no-arg constructor is absent from seeds.txt")
        for field_name in expected_fields:
            checked_fields += 1
            if retained_fields is None or field_name not in retained_fields:
                failures.append(f"{class_name}.{field_name}: field is absent from seeds.txt")
                continue
            # Unchanged names are intentionally omitted from mapping.txt. A present mapping is
            # therefore only an error when it explicitly changes the seeded field name.
            renamed = mapped_fields.get(field_name) if mapped_fields is not None else None
            if renamed is not None and renamed != field_name:
                failures.append(
                    f"{class_name}.{field_name}: R8 renamed the field to {renamed!r}"
                )

    if failures:
        print("R8 JSON contract verification failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(
        f"Verified {checked_fields} durable JSON fields across "
        f"{len(contracts) - len(removed_classes)} retained exact classes "
        f"in {mapping_path} and {seeds_path}."
    )
    if removed_classes:
        print(f"R8 proved {len(removed_classes)} contract classes unreachable and removed them.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
