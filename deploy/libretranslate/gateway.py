"""Narrow LibreTranslate-compatible gateway for Google Cloud Translation."""

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
from urllib.parse import parse_qs
from urllib.request import Request, urlopen


LISTEN_HOST: Final = "0.0.0.0"
LISTEN_PORT: Final = 5000
GOOGLE_TRANSLATE_API_KEY: Final = os.environ.get("GOOGLE_TRANSLATE_API_KEY", "").strip()
GOOGLE_TRANSLATE_URL: Final = "https://translation.googleapis.com/language/translate/v2"
CHARACTER_LIMIT: Final = int(os.environ.get("TRANSLATION_CHAR_LIMIT", "1000"))
REQUESTS_PER_MINUTE: Final = int(os.environ.get("TRANSLATION_REQUESTS_PER_MINUTE", "60"))
MAX_REQUEST_BYTES: Final = 8 * 1024
MAX_TRANSLATION_RESPONSE_BYTES: Final = 64 * 1024
TRANSLATION_TIMEOUT_SECONDS: Final = 10
RATE_WINDOW_SECONDS: Final = 60

GOOGLE_SOURCE_CODES: Final = {
    "ja": "ja",
    "zh-Hans": "zh-CN",
    "ko": "ko",
}

_inference_slot = BoundedSemaphore(value=1)


class SlidingWindowRateLimiter:
    """Bounds total public work; Traefik is the only direct gateway peer."""

    def __init__(self, limit: int, window_seconds: int) -> None:
        self._limit = limit
        self._window_seconds = window_seconds
        self._requests: deque[float] = deque()
        self._lock = Lock()

    def allow(self, now: float) -> bool:
        cutoff = now - self._window_seconds
        with self._lock:
            while self._requests and self._requests[0] <= cutoff:
                self._requests.popleft()
            if len(self._requests) >= self._limit:
                return False
            self._requests.append(now)
            return True


_rate_limiter = SlidingWindowRateLimiter(REQUESTS_PER_MINUTE, RATE_WINDOW_SECONDS)


def translate(source: str, text: str, api_key: str = GOOGLE_TRANSLATE_API_KEY) -> str:
    if not api_key:
        raise ValueError("Google Translation API key is not configured")
    request_body = json.dumps(
        {
            "q": text,
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
    translated = unescape(payload["data"]["translations"][0]["translatedText"]).strip()
    if not translated:
        raise ValueError("Google returned an empty translation")
    return translated


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
        if self.path != "/translate":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if not _rate_limiter.allow(time.monotonic()):
            self._send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Rate limit exceeded"})
            return

        content_type = self.headers.get("Content-Type", "").partition(";")[0].strip().lower()
        if content_type != "application/x-www-form-urlencoded":
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
            fields = parse_qs(
                self.rfile.read(content_length).decode("utf-8"),
                strict_parsing=True,
                max_num_fields=4,
            )
            if set(fields) != {"q", "source", "target", "format"}:
                raise ValueError("Unexpected translation fields")
            if any(len(values) != 1 for values in fields.values()):
                raise ValueError("Repeated translation field")
            text = fields["q"][0].strip()
            source = fields["source"][0]
            if source not in GOOGLE_SOURCE_CODES:
                raise ValueError("Unsupported source language")
            if fields["target"][0] != "en" or fields["format"][0] != "text":
                raise ValueError("Unsupported translation request")
            if not text or len(text) > CHARACTER_LIMIT:
                raise ValueError("Invalid phrase length")
        except (UnicodeDecodeError, ValueError):
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid translation request"})
            return

        if not _inference_slot.acquire(blocking=False):
            self._send_json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "Translator busy"})
            return
        try:
            translated = translate(source, text)
        except (HTTPError, URLError, TimeoutError, KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError):
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Translation unavailable"})
            return
        finally:
            _inference_slot.release()

        self._send_json(HTTPStatus.OK, {"translatedText": translated})

    def log_message(self, message_format: str, *args: object) -> None:
        # Never log the tapped phrase or request body. The default message contains only path/status.
        super().log_message(message_format, *args)

    def _send_json(self, status: HTTPStatus, payload: dict[str, str]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), TranslationHandler).serve_forever()
