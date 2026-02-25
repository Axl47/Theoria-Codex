#!/usr/bin/env python3
"""
Merge a newline-delimited tag list into app/src/main/assets/tag_store.json.

Usage:
  python3 scripts/update_tag_store.py --source PIXIV --input /path/to/tags.txt
  cat tags.txt | python3 scripts/update_tag_store.py --source PIXIV
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any


DEFAULT_STORE = pathlib.Path("app/src/main/assets/tag_store.json")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Update Theoria tag store.")
    parser.add_argument("--source", required=True, choices=["PIXIV", "GELBOORU", "AIBOORU"])
    parser.add_argument("--input", default="-", help="Path to newline-delimited tags, or '-' for stdin.")
    parser.add_argument("--store", default=str(DEFAULT_STORE), help="Path to tag_store.json")
    return parser.parse_args()


def read_lines(path: str) -> list[str]:
    if path == "-":
        body = sys.stdin.read()
    else:
        body = pathlib.Path(path).read_text(encoding="utf-8")
    return [line.strip() for line in body.splitlines() if line.strip() and not line.strip().startswith("#")]


def load_store(path: pathlib.Path) -> dict[str, Any]:
    if not path.exists():
        return {"sources": {}}
    return json.loads(path.read_text(encoding="utf-8"))


def normalize(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: dict[str, dict[str, Any]] = {}
    for entry in entries:
        text = str(entry.get("text", "")).strip()
        if not text:
            continue
        key = text.lower()
        if key not in seen:
            seen[key] = {"text": text, "type": entry.get("type"), "count": entry.get("count")}
    return sorted(seen.values(), key=lambda item: item["text"].lower())


def main() -> int:
    args = parse_args()
    store_path = pathlib.Path(args.store)
    incoming_tags = read_lines(args.input)

    store = load_store(store_path)
    sources = store.setdefault("sources", {})
    current = sources.get(args.source, [])

    incoming = [{"text": tag, "type": "seed"} for tag in incoming_tags]
    sources[args.source] = normalize(current + incoming)

    store_path.parent.mkdir(parents=True, exist_ok=True)
    store_path.write_text(json.dumps(store, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Updated {store_path} for {args.source} with {len(incoming_tags)} incoming tags.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
