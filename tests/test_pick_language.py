# pick_language(): the language the pack gets, and the lines said about a wish that went nowhere (the phone's own language).
# python -m unittest discover tests.
import sys, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))

from porter.pipeline import pick_language   # noqa: E402


class PickLanguage(unittest.TestCase):

    def test_the_asked_language_wins(self):
        lang, notes = pick_language('ja', 'zh-CN', ['ja', 'en-US'])
        self.assertEqual(lang, 'ja')
        self.assertEqual(notes, [])

    def test_the_phone_language_is_a_wish_the_game_keeps(self):
        lang, notes = pick_language('', 'zh-CN', ['zh-Hans', 'ko'])
        self.assertEqual(lang, 'zh-Hans')
        self.assertEqual(notes, [])

    def test_the_phone_language_the_game_lacks_is_said(self):
        lang, notes = pick_language('', 'ja-JP', ['ko', 'en-US'])
        self.assertEqual(lang, 'ko')     # the game's first language
        self.assertEqual(len(notes), 1)
        self.assertIn('ja', notes[0])

    def test_a_phone_language_the_porter_does_not_know_is_said(self):
        lang, notes = pick_language('', 'klingon', ['ko', 'en-US'])
        self.assertEqual(lang, 'en-US')   # the default, the game has it
        self.assertEqual(len(notes), 1)
        self.assertIn('klingon', notes[0])

    def test_no_language_at_all_keeps_the_default(self):
        lang, notes = pick_language('', '', ['ko', 'en-US'])
        self.assertEqual(lang, 'en-US')
        self.assertEqual(notes, [])

    def test_a_game_without_english_keeps_its_first_language(self):
        lang, notes = pick_language('', '', ['ko'])
        self.assertEqual(lang, 'ko')
        self.assertEqual(notes, [])


if __name__ == '__main__':
    unittest.main()
