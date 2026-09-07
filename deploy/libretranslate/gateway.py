"""Narrow current-image batch gateway for Google Cloud Translation."""

from __future__ import annotations

from collections import deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from threading import BoundedSemaphore, Lock
import time
from typing import Final
from html import unescape
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


LISTEN_HOST: Final = "0.0.0.0"
LISTEN_PORT: Final = 5000
GOOGLE_TRANSLATE_API_KEY: Final = os.environ.get("GOOGLE_TRANSLATE_API_KEY", "").strip()
GOOGLE_TRANSLATE_URL: Final = "https://translation.googleapis.com/language/translate/v2"
CHARACTER_LIMIT: Final = int(os.environ.get("TRANSLATION_CHAR_LIMIT", "1000"))
REQUESTS_PER_MINUTE: Final = int(os.environ.get("TRANSLATION_REQUESTS_PER_MINUTE", "60"))
CHARACTERS_PER_DAY: Final = int(os.environ.get("TRANSLATION_CHARACTERS_PER_DAY", "15000"))
MAX_REQUEST_BYTES: Final = 24 * 1024
MAX_TRANSLATION_RESPONSE_BYTES: Final = 64 * 1024
TRANSLATION_TIMEOUT_SECONDS: Final = 10
RATE_WINDOW_SECONDS: Final = 60
DAILY_WINDOW_SECONDS: Final = 24 * 60 * 60
MAX_BATCH_PHRASES: Final = 32
MAX_BATCH_CHARACTERS: Final = 5_000

GOOGLE_SOURCE_CODES: Final = {
    "ja": "ja",
    "zh-Hans": "zh-CN",
    "ko": "ko",
}

_inference_slot = BoundedSemaphore(value=1)


class SlidingWindowBudget:
    """Bounds public work without trusting client-controlled forwarding headers."""

    def __init__(self, limit: int, window_seconds: int) -> None:
        self._limit = limit
        self._window_seconds = window_seconds
        self._usage: deque[tuple[float, int]] = deque()
        self._total = 0
        self._lock = Lock()

    def allow(self, now: float, cost: int = 1) -> bool:
        cutoff = now - self._window_seconds
        with self._lock:
            while self._usage and self._usage[0][0] <= cutoff:
                _, expired_cost = self._usage.popleft()
                self._total -= expired_cost
            if cost <= 0 or self._total + cost > self._limit:
                return False
            self._usage.append((now, cost))
            self._total += cost
            return True


_request_budget = SlidingWindowBudget(REQUESTS_PER_MINUTE, RATE_WINDOW_SECONDS)
_character_budget = SlidingWindowBudget(CHARACTERS_PER_DAY, DAILY_WINDOW_SECONDS)


def translate_batch(
    source: str,
    texts: list[str],
    api_key: str = GOOGLE_TRANSLATE_API_KEY,
) -> list[str]:
    if not api_key:
        raise ValueError("Google Translation API key is not configured")
    request_body = json.dumps(
        {
            "q": texts,
            "source": GOOGLE_SOURCE_CODES[source],
            "target": "en",
            "format": "text",
            "model": "nmt",
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = Request(
        GOOGLE_TRANSLATE_URL,
        data=request_body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "X-Goog-Api-Key": api_key,
        },
        method="POST",
    )
    with urlopen(request, timeout=TRANSLATION_TIMEOUT_SECONDS) as response:
        raw_response = response.read(MAX_TRANSLATION_RESPONSE_BYTES + 1)
    if len(raw_response) > MAX_TRANSLATION_RESPONSE_BYTES:
        raise ValueError("Translation response exceeded the configured limit")
    payload = json.loads(raw_response)
    records = payload["data"]["translations"]
    if not isinstance(records, list) or any(
        not isinstance(item, dict) or not isinstance(item.get("translatedText"), str)
        for item in records
    ):
        raise ValueError("Google returned a malformed translation batch")
    translations = [unescape(item["translatedText"]).strip() for item in records]
    if len(translations) != len(texts) or any(not text for text in translations):
        raise ValueError("Google returned an incomplete translation batch")
    return translations


class TranslationHandler(BaseHTTPRequestHandler):
    server_version = "TheoriaTranslationGateway/1"

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path != "/health":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if GOOGLE_TRANSLATE_API_KEY:
            self._send_json(HTTPStatus.OK, {"status": "ok"})
        else:
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"status": "unconfigured"})

    def do_POST(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path != "/translate-batch":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not _request_budget.allow(time.monotonic()):
            self._send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Rate limit exceeded"})
            return

        content_type = self.headers.get("Content-Type", "").partition(";")[0].strip().lower()
        if content_type != "application/json":
            self._send_json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "Unsupported body"})
            return
        try:
            content_length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            content_length = -1
        if content_length < 0 or content_length > MAX_REQUEST_BYTES:
            self._send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Invalid body size"})
            return

        try:
            body = self.rfile.read(content_length)
            if len(body) != content_length:
                raise ValueError("Incomplete request body")
            fields = json.loads(body.decode("utf-8"))
            if not isinstance(fields, dict) or set(fields) != {"q", "source", "target", "format"}:
                raise ValueError("Unexpected translation fields")
            raw_texts = fields["q"]
            if not isinstance(raw_texts, list) or not 1 <= len(raw_texts) <= MAX_BATCH_PHRASES:
                raise ValueError("Invalid translation batch")
            if any(not isinstance(text, str) for text in raw_texts):
                raise ValueError("Invalid phrase type")
            texts = [text.strip() for text in raw_texts]
            source = fields["source"]
            if source not in GOOGLE_SOURCE_CODES:
                raise ValueError("Unsupported source language")
            if fields["target"] != "en" or fields["format"] != "text":
                raise ValueError("Unsupported translation request")
            if (
                any(not text or len(text) > CHARACTER_LIMIT for text in texts)
                or sum(map(len, texts)) > MAX_BATCH_CHARACTERS
            ):
                raise ValueError("Invalid phrase length")
        except (UnicodeDecodeError, ValueError, TypeError, json.JSONDecodeError):
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid translation request"})
            return
        if not _inference_slot.acquire(blocking=False):
            self._send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Translator busy"})
            return
        try:
            # Busy retries are not translation work and must not consume the daily allowance.
            if not _character_budget.allow(time.monotonic(), sum(map(len, texts))):
                self._send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Daily translation limit reached"})
                return
            translations = translate_batch(source, texts)
        except HTTPError as failure:
            status = (
                HTTPStatus.TOO_MANY_REQUESTS
                if failure.code == HTTPStatus.TOO_MANY_REQUESTS
                else HTTPStatus.SERVICE_UNAVAILABLE
            )
            self._send_json(status, {"error": "Translation unavailable"})
            return
        except (URLError, TimeoutError, KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError):
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Translation unavailable"})
            return
        finally:
            _inference_slot.release()

        self._send_json(HTTPStatus.OK, {"translations": translations})

    def log_message(self, message_format: str, *args: object) -> None:
        # Never log the tapped phrase or request body. The default message contains only path/status.
        super().log_message(message_format, *args)

    def _send_json(self, status: HTTPStatus, payload: dict[str, object]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), TranslationHandler).serve_forever()
