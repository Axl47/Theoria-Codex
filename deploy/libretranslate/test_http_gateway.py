"""Exercise HTTP parsing, admission and recovery without a socket or real upstream."""

from concurrent.futures import ThreadPoolExecutor
from io import BytesIO, StringIO
import json
from threading import BoundedSemaphore, Event
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

import gateway


class RequestSocket:
    def __init__(self, request: bytes) -> None:
        self.request = request
        self.response = bytearray()

    def makefile(self, mode: str, buffering: int) -> BytesIO:
        return BytesIO(self.request)

    def sendall(self, data: bytes) -> None:
        self.response.extend(data)


class TranslationHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        for name, value in {
            "_request_budget": gateway.SlidingWindowBudget(60, 60),
            "_character_budget": gateway.SlidingWindowBudget(15_000, 86_400),
            "_inference_slot": BoundedSemaphore(value=1),
        }.items():
            self.enterContext(patch.object(gateway, name, value))
        self.clock = self.enterContext(patch("gateway.time.monotonic", return_value=100.0))
        self.upstream = self.enterContext(patch("gateway.translate_batch", return_value=["Hello"]))
        self.logs = self.enterContext(patch("sys.stderr", new_callable=StringIO))

    def request(self, fields: object = None, *, body: bytes | None = None,
                headers: dict[str, str] | None = None, path: str = "/translate-batch",
                method: str = "POST") -> tuple[int, dict[str, object]]:
        if fields is None:
            fields = {"q": ["こんにちは"], "source": "ja", "target": "en", "format": "text"}
        payload = json.dumps(fields, ensure_ascii=False).encode() if body is None else body
        actual_headers = {"Content-Type": "application/json", "Content-Length": str(len(payload))}
        actual_headers.update(headers or {})
        lines = [f"{method} {path} HTTP/1.0", *(f"{k}: {v}" for k, v in actual_headers.items())]
        connection = RequestSocket("\r\n".join(lines).encode() + b"\r\n\r\n" + payload)
        gateway.TranslationHandler(connection, ("127.0.0.1", 1), object())
        head, result = bytes(connection.response).split(b"\r\n\r\n", 1)
        self.assertIn(b"Cache-Control: no-store", head)
        self.assertIn(f"Content-Length: {len(result)}".encode(), head)
        return int(head.split(b" ")[1]), json.loads(result)

    def test_normal_request_preserves_order_and_never_logs_phrases(self) -> None:
        self.upstream.return_value = ["Hello", "Thank you"]
        status, result = self.request({
            "q": [" こんにちは ", "ありがとう"], "source": "ja", "target": "en", "format": "text",
        })
        self.assertEqual((200, {"translations": ["Hello", "Thank you"]}), (status, result))
        self.upstream.assert_called_once_with("ja", ["こんにちは", "ありがとう"])
        self.assertNotIn("こんにちは", self.logs.getvalue())
        self.assertNotIn("ありがとう", self.logs.getvalue())

    def test_routes_health_and_missing_configuration_without_translation(self) -> None:
        with patch.object(gateway, "GOOGLE_TRANSLATE_API_KEY", ""):
            self.assertEqual(503, self.request(method="GET", path="/health")[0])
        with patch.object(gateway, "GOOGLE_TRANSLATE_API_KEY", "configured"):
            self.assertEqual((200, {"status": "ok"}), self.request(method="GET", path="/health"))
        self.assertEqual(404, self.request(path="/translate")[0])
        self.assertEqual(404, self.request(method="GET", path="/unknown")[0])
        self.upstream.assert_not_called()

    def test_invalid_payloads_are_rejected_before_upstream_work(self) -> None:
        valid = {"q": ["こんにちは"], "source": "ja", "target": "en", "format": "text"}
        invalid = [
            [], None, {**valid, "url": "https://example.invalid/private"},
            {key: value for key, value in valid.items() if key != "format"},
            *({**valid, "q": value} for value in [[], "text", [1], [None], [" "], ["あ" * 1001], ["あ"] * 33]),
            {**valid, "q": ["あ" * 900] * 6}, {**valid, "source": "en"},
            {**valid, "source": []}, {**valid, "target": "ja"}, {**valid, "format": "html"},
        ]
        for index, fields in enumerate(invalid):
            with self.subTest(case=index):
                self.assertEqual(400, self.request(body=json.dumps(fields, ensure_ascii=False).encode())[0])
        for body in [b"{", b"\xff"]:
            self.assertEqual(400, self.request(body=body)[0])
        self.upstream.assert_not_called()

    def test_body_transport_limits_reject_before_upstream_work(self) -> None:
        self.assertEqual(415, self.request(headers={"Content-Type": "text/plain"})[0])
        for length in ["", "-1", "invalid", str(gateway.MAX_REQUEST_BYTES + 1)]:
            with self.subTest(length=length):
                self.assertEqual(413, self.request(headers={"Content-Length": length})[0])
        self.assertEqual(400, self.request(headers={"Content-Length": "1000"})[0])
        self.upstream.assert_not_called()

    def test_exact_phrase_and_character_bounds_accept_complete_batches(self) -> None:
        for phrases in [["あ"] * 32, ["あ" * 1000] * 5]:
            with self.subTest(count=len(phrases)):
                self.upstream.return_value = ["Translated"] * len(phrases)
                status, result = self.request({
                    "q": phrases, "source": "ja", "target": "en", "format": "text",
                })
                self.assertEqual(200, status)
                self.assertEqual(len(phrases), len(result["translations"]))
                self.assertEqual(phrases, self.upstream.call_args.args[1])

    def test_daily_and_request_budgets_recover_at_their_window_boundaries(self) -> None:
        with patch.object(gateway, "_request_budget", gateway.SlidingWindowBudget(1, 60)):
            self.assertEqual(200, self.request()[0])
            self.assertEqual(429, self.request()[0])
            self.clock.return_value = 160.0
            self.assertEqual(200, self.request()[0])
        with patch.object(gateway, "_character_budget", gateway.SlidingWindowBudget(5, 86_400)):
            self.assertEqual(200, self.request()[0])
            self.assertEqual(429, self.request()[0])
            self.clock.return_value = 86_560.0
            self.assertEqual(200, self.request()[0])
        self.assertEqual(4, self.upstream.call_count)

    def test_busy_request_does_not_spend_daily_budget_and_slot_recovers(self) -> None:
        entered, release = Event(), Event()

        def translate(source: str, texts: list[str]) -> list[str]:
            entered.set()
            if not release.wait(5):
                raise AssertionError("Test did not release the admitted request")
            return ["Hello"]

        self.upstream.side_effect = translate
        with patch.object(gateway, "_character_budget", gateway.SlidingWindowBudget(10, 86_400)):
            with ThreadPoolExecutor(max_workers=1) as executor:
                first = executor.submit(self.request)
                try:
                    self.assertTrue(entered.wait(5))
                    self.assertEqual((429, {"error": "Translator busy"}), self.request())
                    self.assertEqual(1, self.upstream.call_count)
                finally:
                    release.set()
                self.assertEqual(200, first.result(timeout=5)[0])
            self.assertEqual(200, self.request()[0])
            self.assertEqual(2, self.upstream.call_count)

    def test_upstream_failures_return_retryable_status_and_release_admission(self) -> None:
        failures = [
            (TimeoutError(), 503), (URLError("unreachable"), 503),
            (ValueError("malformed response"), 503),
            (HTTPError("https://upstream.invalid", 429, "busy", {}, None), 429),
            (HTTPError("https://upstream.invalid", 500, "failed", {}, None), 503),
        ]
        for failure, expected in failures:
            with self.subTest(failure=failure):
                self.upstream.side_effect = failure
                self.assertEqual((expected, {"error": "Translation unavailable"}), self.request())
                self.upstream.side_effect = None
                self.assertEqual(200, self.request()[0])


if __name__ == "__main__":
    unittest.main()
