from io import BytesIO
import json
import unittest
from unittest.mock import patch

from gateway import GOOGLE_TRANSLATE_URL, SlidingWindowBudget, translate_batch


class FakeResponse:
    def __init__(self, payload: dict[str, object]) -> None:
        self._body = BytesIO(json.dumps(payload).encode("utf-8"))

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *args: object) -> None:
        pass

    def read(self, size: int = -1) -> bytes:
        return self._body.read(size)


class GoogleTranslationTest(unittest.TestCase):
    def test_sends_only_the_phrase_batch_and_language_pair_with_key_in_a_header(self) -> None:
        response = FakeResponse(
            {
                "data": {
                    "translations": [
                        {"translatedText": "That&#39;s impossible!"},
                        {"translatedText": "Ah!"},
                    ],
                },
            },
        )

        with patch("gateway.urlopen", return_value=response) as request:
            result = translate_batch(
                "ja",
                ["そんなの無理だよ！", "あっ"],
                api_key="secret-key",
            )

        outgoing = request.call_args.args[0]
        self.assertEqual(GOOGLE_TRANSLATE_URL, outgoing.full_url)
        self.assertEqual("secret-key", outgoing.get_header("X-goog-api-key"))
        self.assertEqual(
            {
                "q": ["そんなの無理だよ！", "あっ"],
                "source": "ja",
                "target": "en",
                "format": "text",
                "model": "nmt",
            },
            json.loads(outgoing.data),
        )
        self.assertEqual(["That's impossible!", "Ah!"], result)

    def test_maps_simplified_chinese_to_the_google_nmt_code(self) -> None:
        response = FakeResponse({"data": {"translations": [{"translatedText": "Hello"}]}})

        with patch("gateway.urlopen", return_value=response) as request:
            translate_batch("zh-Hans", ["你好"], api_key="secret-key")

        outgoing = json.loads(request.call_args.args[0].data)
        self.assertEqual("zh-CN", outgoing["source"])

    def test_rejects_translation_without_a_server_credential(self) -> None:
        with self.assertRaisesRegex(ValueError, "not configured"):
            translate_batch("ko", ["안녕하세요"], api_key="")


class SlidingWindowBudgetTest(unittest.TestCase):
    def test_rejects_excess_weighted_work_until_the_window_expires(self) -> None:
        limiter = SlidingWindowBudget(limit=3, window_seconds=60)

        self.assertTrue(limiter.allow(100, cost=2))
        self.assertTrue(limiter.allow(101, cost=1))
        self.assertFalse(limiter.allow(102, cost=1))
        self.assertTrue(limiter.allow(161, cost=3))


if __name__ == "__main__":
    unittest.main()
