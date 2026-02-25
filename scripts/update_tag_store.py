#!/usr/bin/env python3
"""
Merge tags into app/src/main/assets/tag_store.json.

Usage:
  python3 scripts/update_tag_store.py --source PIXIV --input /path/to/tags.txt
  cat tags.txt | python3 scripts/update_tag_store.py --source PIXIV
  python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-url
  python3 scripts/update_tag_store.py --source PIXIV --pixiv-tags-html /path/to/pixiv-tags-page.html
"""

from __future__ import annotations

import argparse
import html
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request
from typing import Any


DEFAULT_STORE = pathlib.Path("app/src/main/assets/tag_store.json")
DEFAULT_PIXIV_TAGS_URL = "https://www.pixiv.net/en/tags"
PIXIV_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Update Theoria tag store.")
    parser.add_argument("--source", required=True, choices=["PIXIV", "GELBOORU", "AIBOORU"])
    parser.add_argument("--input", default="-", help="Path to newline-delimited tags, or '-' for stdin.")
    parser.add_argument(
        "--pixiv-tags-url",
        nargs="?",
        const=DEFAULT_PIXIV_TAGS_URL,
        default=None,
        help="Fetch Pixiv tags page and merge discovered tags (PIXIV source only).",
    )
    parser.add_argument(
        "--pixiv-tags-html",
        default=None,
        help="Path to saved HTML of Pixiv /en/tags page to parse (PIXIV source only).",
    )
    parser.add_argument(
        "--request-timeout",
        type=int,
        default=15,
        help="HTTP timeout in seconds for URL fetches.",
    )
    parser.add_argument("--store", default=str(DEFAULT_STORE), help="Path to tag_store.json")
    return parser.parse_args()


def read_lines(path: str) -> list[str]:
    if path == "-":
        if sys.stdin.isatty():
            return []
        body = sys.stdin.read()
    else:
        body = pathlib.Path(path).read_text(encoding="utf-8")
    return [line.strip() for line in body.splitlines() if line.strip() and not line.strip().startswith("#")]


def fetch_url(url: str, timeout_seconds: int) -> str:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": PIXIV_USER_AGENT,
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:  # nosec B310
        raw = response.read()
    return raw.decode("utf-8", errors="replace")


def is_cloudflare_challenge(html_body: str) -> bool:
    lowered = html_body.lower()
    return (
        "just a moment..." in lowered and
        "_cf_chl_opt" in lowered and
        "challenge-platform" in lowered
    )


def extract_pixiv_tags_from_html(html_body: str) -> list[str]:
    candidates: list[str] = []

    # Typical Pixiv tag page links look like /en/tags/<tag>/artworks.
    candidates.extend(
        re.findall(r'href="/(?:[a-z]{2}/)?tags/([^"/?#]+)', html_body, flags=re.IGNORECASE)
    )
    # Some payloads expose tag values in JSON blobs.
    candidates.extend(re.findall(r'"tag"\s*:\s*"([^"]+)"', html_body))

    normalized: list[str] = []
    seen: set[str] = set()
    for candidate in candidates:
        text = html.unescape(urllib.parse.unquote(candidate)).strip()
        if not text:
            continue
        if len(text) > 120:
            continue
        key = text.lower()
        if key in seen:
            continue
        seen.add(key)
        normalized.append(text)
    return normalized


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
            normalized: dict[str, Any] = {"text": text}
            tag_type = entry.get("type")
            if tag_type is not None:
                normalized["type"] = tag_type
            tag_count = entry.get("count")
            if tag_count is not None:
                normalized["count"] = tag_count
            seen[key] = normalized
    return sorted(seen.values(), key=lambda item: item["text"].lower())


def main() -> int:
    args = parse_args()
    store_path = pathlib.Path(args.store)
    incoming_entries = [{"text": tag, "type": "seed"} for tag in read_lines(args.input)]

    if args.pixiv_tags_url or args.pixiv_tags_html:
        if args.source != "PIXIV":
            raise SystemExit("--pixiv-tags-url/--pixiv-tags-html are only valid with --source PIXIV")

    if args.pixiv_tags_html:
        html_path = pathlib.Path(args.pixiv_tags_html)
        html_body = html_path.read_text(encoding="utf-8")
        page_tags = extract_pixiv_tags_from_html(html_body)
        incoming_entries.extend({"text": tag, "type": "pixiv_tags_page"} for tag in page_tags)

    if args.pixiv_tags_url:
        html_body = fetch_url(args.pixiv_tags_url, timeout_seconds=args.request_timeout)
        if is_cloudflare_challenge(html_body):
            raise SystemExit(
                "Pixiv returned an anti-bot challenge page. "
                "Open the URL in a browser, save the HTML, then rerun with --pixiv-tags-html."
            )
        page_tags = extract_pixiv_tags_from_html(html_body)
        incoming_entries.extend({"text": tag, "type": "pixiv_tags_page"} for tag in page_tags)

    store = load_store(store_path)
    sources = store.setdefault("sources", {})
    current = sources.get(args.source, [])

    sources[args.source] = normalize(current + incoming_entries)

    store_path.parent.mkdir(parents=True, exist_ok=True)
    store_path.write_text(json.dumps(store, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Updated {store_path} for {args.source} with {len(incoming_entries)} incoming tags.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
