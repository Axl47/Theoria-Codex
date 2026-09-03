import unittest

from gateway import SlidingWindowRateLimiter, translation_prompt


class TranslationPromptTest(unittest.TestCase):
    def test_builds_manga_aware_prompt_without_changing_source_text(self) -> None:
        text = "我が家の叡智は最高すぎる！"

        prompt = translation_prompt("ja", text)

        self.assertIn("Japanese manga dialogue", prompt)
        self.assertTrue(prompt.endswith(text))
        self.assertEqual(1, prompt.count(text))

    def test_each_supported_source_uses_the_correct_comic_context(self) -> None:
        self.assertIn("Japanese manga", translation_prompt("ja", "text"))
        self.assertIn("Chinese comic", translation_prompt("zh-Hans", "text"))
        self.assertIn("Korean manhwa", translation_prompt("ko", "text"))


class SlidingWindowRateLimiterTest(unittest.TestCase):
    def test_rejects_excess_work_until_the_window_expires(self) -> None:
        limiter = SlidingWindowRateLimiter(limit=2, window_seconds=60)

        self.assertTrue(limiter.allow(100))
        self.assertTrue(limiter.allow(101))
        self.assertFalse(limiter.allow(102))
        self.assertTrue(limiter.allow(161))


if __name__ == "__main__":
    unittest.main()
