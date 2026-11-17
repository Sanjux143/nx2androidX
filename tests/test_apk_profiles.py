"""The app does not carry the game profiles: they are imported by the player, and the tools that read a profile find them there."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('phone_assets', Path(__file__).resolve().parents[1] / 'packaging/android-apk/build_assets.py')
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class ApkProfilesTest(unittest.TestCase):
    def test_a_profile_folder_is_the_one_named_after_a_title_id(self):
        self.assertTrue(assets.in_profile_dir(Path('nxnative/dm/games/0100000000010000/game.tsv')))
        self.assertTrue(assets.in_profile_dir(Path('nxnative/dm/games/0100000000010000')))
        self.assertFalse(assets.in_profile_dir(Path('nxnative/dm/games/game.tsv')))
        self.assertFalse(assets.in_profile_dir(Path('nxnative/dm/games/settings.tsv')))
        self.assertFalse(assets.in_profile_dir(Path('nxnative/dm/android/tools/android_profile.py')))
        self.assertFalse(assets.in_profile_dir(Path('porter/profiles.py')))

    def test_the_copy_leaves_the_profiles_out_and_keeps_the_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'nxnative/dm/games'
            (source / '0100000000010000').mkdir(parents=True)
            (source / '0100000000010000' / 'game.tsv').write_text('title_id\t0100000000010000\n')
            (source / '0100000000010000' / 'shader_map.tsv').write_text('a\tb\n')
            (source / 'game.py').write_text('# the profile loader\n')
            (source / 'settings.tsv').write_text('# the settings\n')
            assets.copy_tree(root, root / 'out')
            out = root / 'out/nxnative/dm/games'
            self.assertTrue((out / 'game.py').is_file())          # the tools that read a profile travel
            self.assertTrue((out / 'settings.tsv').is_file())
            self.assertFalse((out / '0100000000010000').exists())   # the profiles do not


if __name__ == '__main__':
    unittest.main()
