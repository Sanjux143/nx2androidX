"""The player's own game profiles: the package they import, the layer it lands in, and the profiles the tools find there."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from porter import profiles_layer


def package(entries: dict, manifest=None) -> Path:
    """A profiles package: the given entries (path in the zip -> bytes) and, when given, a manifest."""
    path = Path(tempfile.mkdtemp()) / 'profiles.zip'
    with zipfile.ZipFile(path, 'w') as zf:
        if manifest is not None: zf.writestr('manifest.json', json.dumps(manifest))
        for name, data in entries.items(): zf.writestr(name, data)
    return path


class ProfilesLayerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.env = dict(os.environ, NX2A_HOME=str(self.home))
        self.patch = mock.patch.dict(os.environ, self.env, clear=False)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        os.environ.pop('DM_GAMES_DIR', None)

    def a_package(self, title='0100000000010000', **extra) -> Path:
        entries = {f'{title}/game.tsv': b'title_id\t0100000000010000\n', f'{title}/README.md': b'notes\n'}
        entries.update(extra)
        return package(entries, manifest={'revision': 3, 'commit': 'abc123', 'date': '2026-01-02'})

    def test_the_import_writes_the_profiles_and_a_receipt(self):
        rec = profiles_layer.import_zip(self.a_package())
        self.assertEqual(rec['count'], 1)
        self.assertEqual(rec['revision'], 3)          # the package's own revision, not ours
        self.assertEqual(rec['commit'], 'abc123')
        self.assertEqual(profiles_layer.imported(), ['0100000000010000'])
        self.assertTrue((profiles_layer.layer_dir() / '0100000000010000' / 'game.tsv').is_file())

    def test_the_layer_joins_the_search_path(self):
        profiles_layer.activate()
        self.assertIn(str(profiles_layer.layer_dir()), os.environ['DM_GAMES_DIR'].split(os.pathsep))
        profiles_layer.activate()   # twice: the layer is not added twice
        self.assertEqual(os.environ['DM_GAMES_DIR'].split(os.pathsep).count(str(profiles_layer.layer_dir())), 1)

    def test_the_environment_the_tools_get_carries_the_layer(self):
        env = {}
        profiles_layer.activate(env)
        self.assertIn(str(profiles_layer.layer_dir()), env['DM_GAMES_DIR'].split(os.pathsep))

    def test_a_package_that_leaves_the_layer_is_refused(self):
        path = package({'../../etc/passwd': b'x', '0100000000010000/game.tsv': b'title_id\t0100000000010000\n'})
        with self.assertRaises(profiles_layer.LayerError) as e:
            profiles_layer.import_zip(path)
        self.assertIn('passwd', str(e.exception))
        self.assertEqual(profiles_layer.imported(), [])   # nothing was written

    def test_a_folder_without_a_game_tsv_is_refused(self):
        path = package({'0100000000010000/README.md': b'notes\n'})
        with self.assertRaises(profiles_layer.LayerError) as e:
            profiles_layer.import_zip(path)
        self.assertIn('game.tsv', str(e.exception))
        self.assertEqual(profiles_layer.imported(), [])

    def test_a_file_outside_a_profile_folder_is_refused(self):
        path = package({'README.md': b'x', '0100000000010000/game.tsv': b'title_id\t0100000000010000\n'})
        with self.assertRaises(profiles_layer.LayerError):
            profiles_layer.import_zip(path)

    def test_a_package_that_is_not_a_package_is_refused(self):
        with self.assertRaises(profiles_layer.LayerError):
            profiles_layer.import_zip(package({'0100000000010000/notes.txt': b'x'}))

    def test_the_repository_layout_is_accepted(self):
        path = package({'dm/games/0100000000010000/game.tsv': b'title_id\t0100000000010000\n'})
        rec = profiles_layer.import_zip(path)
        self.assertEqual(rec['ids'], ['0100000000010000'])
        self.assertTrue((profiles_layer.layer_dir() / '0100000000010000' / 'game.tsv').is_file())

    def test_a_missing_file_is_refused(self):
        with self.assertRaises(profiles_layer.LayerError):
            profiles_layer.import_zip(self.home / 'nothing.zip')

    def test_the_receipt_carries_the_package_digest(self):
        # The app carries the digest of the package its build made; the receipt carries the digest of the package the
        # player imported. The two are compared, so the player can be told whether they are the same one.
        path = self.a_package()
        rec = profiles_layer.import_zip(path)
        self.assertEqual(rec['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(rec['package'], path.name)
        self.assertEqual(profiles_layer.receipt()['sha256'], rec['sha256'])

    def test_the_package_is_cut_with_the_digest_the_app_carries(self):
        # The app's build cuts the package and writes its manifest (the name, the digest); the importer reads the same
        # digest out of the package it was given.
        spec = importlib.util.spec_from_file_location('profiles_zip', Path(__file__).resolve().parents[1] / 'devtools/profiles_zip.py')
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        games = self.home / 'games'
        (games / '0100000000010000').mkdir(parents=True)
        (games / '0100000000010000' / 'game.tsv').write_text('title_id\t0100000000010000\n')
        manifest, path = mod.cut(games, self.home / 'pkg.zip')
        self.assertEqual(manifest['count'], 1)
        self.assertEqual(manifest['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        rec = profiles_layer.import_zip(path)
        self.assertEqual(rec['sha256'], manifest['sha256'])
        self.assertEqual(rec['revision'], manifest['revision'])


if __name__ == '__main__':
    unittest.main()
