"""Tests for commands.py: python3 -m unittest discover -s server."""

import json
import unittest
from pathlib import Path

from commands import Commands, Hit, norm

CONFIG = {
    "trigger_scan_code": 63,
    "launch_verbs": ["запусти", "открой"],
    "search": {"prefixes": ["найди", "поищи"], "package": "top.rootu.lampa"},
    "commands": [
        {"phrases": ["ютуб"], "launch": "com.google.android.youtube.tv"},
        {"phrases": ["выключи звук", "включи звук"], "volume": "mute"},
        {"phrases": ["звук"], "volume": "up"},
        {"phrases": ["вперёд"], "key": "KEYCODE_MEDIA_NEXT"},
        {"phrases": ["назад"], "global": "back"},
    ],
}


def make(cfg: object = None) -> Commands:
    return Commands(json.dumps(CONFIG if cfg is None else cfg, ensure_ascii=False))


class NormTest(unittest.TestCase):
    def test_folds_case_yo_punctuation_spaces(self) -> None:
        self.assertEqual(norm("  Вперёд,  ЮТУБ! "), "вперед ютуб")

    def test_none_is_empty(self) -> None:
        self.assertEqual(norm(None), "")


class GrammarTest(unittest.TestCase):
    def test_launch_gets_verbs_and_raw_spelling_is_kept(self) -> None:
        g = json.loads(make().grammar)
        self.assertIn("ютуб", g)
        self.assertIn("запусти ютуб", g)
        self.assertIn("открой ютуб", g)
        self.assertNotIn("запусти назад", g)  # verbs only for launch
        self.assertIn("вперёд", g)  # model vocabulary has "вперёд", not "вперед"
        self.assertEqual(g[-1], "[unk]")


class MatchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.c = make()

    def test_exact(self) -> None:
        self.assertEqual(self.c.match("ютуб", "ютуб"), Hit("launch", "com.google.android.youtube.tv"))

    def test_contained_as_whole_words(self) -> None:
        self.assertEqual(self.c.match("открой ютуб", ""), Hit("launch", "com.google.android.youtube.tv"))

    def test_longest_phrase_wins(self) -> None:
        self.assertEqual(self.c.match("выключи звук", ""), Hit("volume", "mute"))

    def test_no_partial_word_match(self) -> None:
        self.assertIsNone(self.c.match("назадний", "назадний"))

    def test_yo_folded(self) -> None:
        self.assertEqual(self.c.match("вперёд", ""), Hit("key", "KEYCODE_MEDIA_NEXT"))

    def test_falls_back_to_free_text(self) -> None:
        self.assertEqual(self.c.match("[unk]", "ну назад"), Hit("global", "back"))

    def test_search_uses_free_text_and_wins(self) -> None:
        self.assertEqual(
            self.c.match("[unk] ютуб", "найди реквием по мечте"),
            Hit("search", "top.rootu.lampa", "реквием по мечте"),
        )

    def test_bare_prefix_is_not_a_search(self) -> None:
        self.assertIsNone(self.c.match("", "найди"))

    def test_nothing(self) -> None:
        self.assertIsNone(self.c.match("", ""))


class ValidationTest(unittest.TestCase):
    def test_two_actions_rejected(self) -> None:
        bad = {"commands": [{"phrases": ["x"], "launch": "a", "volume": "up"}]}
        with self.assertRaisesRegex(ValueError, "exactly one action"):
            make(bad)

    def test_no_action_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "exactly one action"):
            make({"commands": [{"phrases": ["x"]}]})

    def test_bad_volume_arg_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "volume"):
            make({"commands": [{"phrases": ["x"], "volume": "louder"}]})

    def test_defaults(self) -> None:
        c = make({"commands": []})
        self.assertEqual(c.trigger_scan_code, 63)
        self.assertIsNone(c.match("", "найди матрица"))


class ShippedConfigTest(unittest.TestCase):
    def test_repo_commands_json_is_valid(self) -> None:
        c = Commands((Path(__file__).parent / "commands.json").read_text(encoding="utf-8"))
        self.assertEqual(c.match("запусти ютуб", ""), Hit("launch", "com.google.android.youtube.tv"))


if __name__ == "__main__":
    unittest.main()
