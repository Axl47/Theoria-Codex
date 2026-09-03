"""Narrow LibreTranslate-compatible gateway for the private Hy-MT2 model service."""

from __future__ import annotations

from collections import deque
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from threading import BoundedSemaphore, Lock
import time
from typing import Final
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs
from urllib.request import Request, urlopen


LISTEN_HOST: Final = "0.0.0.0"
LISTEN_PORT: Final = 5000
MODEL_BASE_URL: Final = os.environ.get("HYMT2_BASE_URL", "http://hymt2:8080").rstrip("/")
CHARACTER_LIMIT: Final = int(os.environ.get("TRANSLATION_CHAR_LIMIT", "1000"))
REQUESTS_PER_MINUTE: Final = int(os.environ.get("TRANSLATION_REQUESTS_PER_MINUTE", "60"))
MAX_REQUEST_BYTES: Final = 8 * 1024
MAX_MODEL_RESPONSE_BYTES: Final = 64 * 1024
MODEL_TIMEOUT_SECONDS: Final = 20
RATE_WINDOW_SECONDS: Final = 60

SOURCE_CONTEXT: Final = {
    "ja": "Japanese manga dialogue",
    "zh": "Simplified Chinese comic dialogue",
    "zh-Hans": "Simplified Chinese comic dialogue",
    "ko": "Korean manhwa dialogue",
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


def translation_prompt(source: str, text: str) -> str:
    context = SOURCE_CONTEXT[source]
    return (
        f"Translate the following {context} into natural English. "
        "Correct only obvious OCR spacing artifacts. Preserve the meaning, tone, names, "
        "honorifics, sound effects, and punctuation. Do not censor, summarize, or add content. "
        "Only output the translated result without any additional explanation:\n"
        f"{text}"
    )


def translate(source: str, text: str) -> str:
    request_body = json.dumps(
        {
            "model": "Hy-MT2-1.8B",
            "messages": [{"role": "user", "content": translation_prompt(source, text)}],
            "temperature": 0.7,
            "top_p": 0.6,
            "top_k": 20,
            "repeat_penalty": 1.05,
            "max_tokens": 256,
            "stream": False,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    request = Request(
        f"{MODEL_BASE_URL}/v1/chat/completions",
        data=request_body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=MODEL_TIMEOUT_SECONDS) as response:
        raw_response = response.read(MAX_MODEL_RESPONSE_BYTES + 1)
    if len(raw_response) > MAX_MODEL_RESPONSE_BYTES:
        raise ValueError("Model response exceeded the configured limit")
    payload = json.loads(raw_response)
    translated = payload["choices"][0]["message"]["content"].strip()
    if not translated:
        raise ValueError("Model returned an empty translation")
    return translated


def model_is_ready() -> bool:
    try:
        with urlopen(f"{MODEL_BASE_URL}/health", timeout=2) as response:
            return response.status == HTTPStatus.OK
    except (HTTPError, URLError, TimeoutError):
        return False


class TranslationHandler(BaseHTTPRequestHandler):
    server_version = "TheoriaTranslationGateway/1"

    def do_GET(self) -> None:  # noqa: N802 - required by BaseHTTPRequestHandler
        if self.path != "/health":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})
            return
        if model_is_ready():
            self._send_json(HTTPStatus.OK, {"status": "ok"})
        else:
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"status": "loading"})

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
            if source not in SOURCE_CONTEXT:
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
