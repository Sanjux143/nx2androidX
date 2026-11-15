"""Runtime updates rebuild the runtime with the profile that made the pack, and refuse one that does not match."""
import argparse
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from porter import runtime_update
from porter.revision import PLAYER_API_REVISION


class RuntimeUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pack = self.root / 'Game-data'
        self.pack.mkdir()
        self.title = '0100000000010000'
        self.manifest = self.pack / 'nx2a-data.json'
        self.manifest.write_text(json.dumps({'title_id': self.title, 'name': 'Game',
            'host': 'host/libdmplay.so', 'host_front': 2, 'player_api_revision': 0}))
        for name in ('host/libdmplay.so', 'mods/main', 'shaders/a', 'romfs/file', 'dlc/a',
                     'saves/slot', 'settings.txt', 'drivers/custom.so'):
            p = self.pack / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'preserve me')
        self.dm = self.root / 'tool' / 'nxnative' / 'dm'
        (self.dm / 'android').mkdir(parents=True)
        (self.dm / 'games').mkdir(parents=True)
        # the fake profile header is the profile's own game.tsv: the test computes the fingerprint the same way
        (self.dm / 'games' / 'game.py').write_text('''import pathlib, sys
out = pathlib.Path(sys.argv[3]); d = pathlib.Path(sys.argv[2])
out.write_text((d / 'game.tsv').read_text())
''')
        self.build = self.dm / 'android' / 'build.py'
        self.build.write_text('''import os, pathlib, sys, struct
args = sys.argv
assert '--play' in args and '--release' in args
assert os.environ['DM_GAMES_FIRST'] == '1'
profile = pathlib.Path(os.environ['DM_GAMES_DIR']) / os.environ['DM_GAME']
assert (profile / 'game.tsv').read_text() == 'effective game profile with adjusted memory'
assert (profile / 'android.tsv').read_text() == 'effective android profile'
assert not list(profile.glob('*.h'))
out = pathlib.Path(args[args.index('--out')+1]); out.mkdir()
header = bytearray(64); header[:6] = b'\\x7fELF\\x02\\x01'; struct.pack_into('<H', header, 18, 183)
(out / 'libdmplay.so').write_bytes(header)
for name in ('libhook_impl.so', 'libmain_hook.so', 'libfile_redirect_hook.so', 'libgsl_alloc_hook.so'):
    (out / name).write_bytes(header)
''')
        self.before = {str(p.relative_to(self.pack)): p.read_bytes() for p in self.pack.rglob('*') if p.is_file()}
        self.addCleanup(patch.stopall)
        patch.object(runtime_update, 'DM', self.dm).start()
        patch.object(runtime_update.config, 'load', return_value={}).start()
        patch.object(runtime_update.toolchain, 'find_ndk', return_value=(self.root / 'ndk', 'test')).start()
        patch.dict(os.environ, {'NX2A_WORK': str(self.root / 'work'),
                   'NX2A_PLAYER_API_REVISION': str(PLAYER_API_REVISION)}).start()

    def profile(self, where, text='effective game profile with adjusted memory'):
        """A profile folder (its name is the title ID) and the fingerprint its header has"""
        d = where / self.title
        d.mkdir(parents=True, exist_ok=True)
        (d / 'game.tsv').write_text(text)
        (d / 'android.tsv').write_text('effective android profile')
        return hashlib.sha256(text.encode()).hexdigest()

    def record(self, fingerprint):
        self.manifest.write_text(json.dumps({'title_id': self.title, 'name': 'Game',
            'host': 'host/libdmplay.so', 'host_front': 2, 'player_api_revision': 0, 'profile_h': fingerprint}))
        self.before['nx2a-data.json'] = self.manifest.read_bytes()   # the manifest the test wrote is the baseline

    def rebuild(self, allow_drift=False):
        return runtime_update.rebuild(self.pack, no_lsfg=True, jobs=1, allow_profile_drift=allow_drift, emit=lambda _: None)

    def assert_preserved(self, manifest=True):
        for name, data in self.before.items():
            if manifest or name != 'nx2a-data.json':
                self.assertEqual((self.pack / name).read_bytes(), data, name)

    def test_update_without_source_keys_or_old_work(self):
        self.assertFalse((self.root / 'work').exists())
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        result = self.rebuild()
        self.assertEqual(result['data'], str(self.pack.resolve()))
        updated = json.loads(self.manifest.read_text())
        self.assertEqual(updated['player_api_revision'], PLAYER_API_REVISION)
        self.assertNotIn('host_front', updated)
        host = self.pack / updated['host']
        self.assertTrue(host.parent.name.startswith('.runtime-'))
        self.assertTrue(all((host.parent / name).is_file() for name in runtime_update.HOOKS))
        self.assert_preserved(manifest=False)

    def test_the_workspace_profile_is_the_one_that_made_the_pack(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        self.rebuild()
        self.assert_preserved(manifest=False)

    def test_the_payload_profile_is_used_when_the_workspace_has_none(self):
        fingerprint = self.profile(self.dm / 'games')
        self.record(fingerprint)
        self.rebuild()
        self.assert_preserved(manifest=False)

    def test_a_changed_profile_is_refused_before_compilation(self):
        self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived', 'another game profile')
        self.record(hashlib.sha256(b'effective game profile with adjusted memory').hexdigest())
        with self.assertRaisesRegex(ValueError, 'profile changed since this pack was built'):
            self.rebuild()
        self.assert_preserved()
        self.assertEqual(list(self.pack.glob('.runtime-*')), [])

    def test_a_changed_profile_is_rebuilt_with_the_current_profile_when_the_player_asks_for_it(self):
        current = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(hashlib.sha256(b'a profile from an earlier port').hexdigest())
        said = []
        result = runtime_update.rebuild(self.pack, no_lsfg=True, jobs=1, allow_profile_drift=True, emit=said.append)
        self.assertEqual(result['data'], str(self.pack.resolve()))
        updated = json.loads(self.manifest.read_text())
        self.assertEqual(updated['profile_h'], current)   # the manifest names the profile it was built from now
        self.assertTrue(any('Rebuilding the runtime with the current profile' in line for line in said))
        self.assert_preserved(manifest=False)

    def test_no_profile_anywhere_is_refused_even_when_the_player_asks_for_it(self):
        self.record(hashlib.sha256(b'effective game profile with adjusted memory').hexdigest())
        with self.assertRaisesRegex(ValueError, 'No game profile for this game'):
            self.rebuild(allow_drift=True)
        self.assert_preserved()

    def test_the_drift_is_reported_as_an_event_the_app_can_act_on(self):
        from porter import cli
        args = argparse.Namespace(pack=str(self.pack), progress='json', no_lsfg=True, jobs=1,
                                  allow_profile_drift=False)
        with patch.object(runtime_update, 'rebuild',
                          side_effect=runtime_update.ProfileDrift('The game profile changed since this pack was built.')):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = cli.cmd_rebuild_runtime(args)
        self.assertEqual(code, 1)
        events = [json.loads(line) for line in out.getvalue().splitlines() if line.startswith('{')]
        self.assertTrue(any(event.get('ev') == 'profile-drift' for event in events), events)

    def test_no_profile_anywhere_is_refused_before_compilation(self):
        self.record(hashlib.sha256(b'effective game profile with adjusted memory').hexdigest())
        with self.assertRaisesRegex(ValueError, 'No game profile for this game'):
            self.rebuild()
        self.assert_preserved()

    def test_a_pack_without_a_fingerprint_is_trusted_and_gets_one(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.rebuild()
        updated = json.loads(self.manifest.read_text())
        self.assertEqual(updated['profile_h'], fingerprint)   # the profile the runtime was built from is recorded now
        self.assert_preserved(manifest=False)

    def test_a_pack_without_a_fingerprint_fails_when_no_profile_is_anywhere(self):
        with self.assertRaisesRegex(ValueError, 'No game profile for this game'):
            self.rebuild()
        self.assert_preserved()

    def test_failed_compilation_keeps_every_original_byte(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        self.build.write_text('raise SystemExit(1)')
        with self.assertRaisesRegex(RuntimeError, 'Runtime compilation failed'):
            self.rebuild()
        self.assert_preserved()
        self.assertEqual(list(self.pack.glob('.runtime-*')), [])

    def test_interrupted_activation_and_retry(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        with patch.object(runtime_update.os, 'replace', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.rebuild()
        self.assert_preserved()
        # A hard-killed process may leave an unreferenced stage; it cannot become active by discovery.
        orphan = self.pack / '.runtime-orphan'
        orphan.mkdir()
        (orphan / 'libdmplay.so').write_bytes(b'incomplete')
        self.rebuild()
        self.assertNotIn('orphan', json.loads(self.manifest.read_text())['host'])
        self.assert_preserved(manifest=False)

    def test_cancellation_immediately_after_commit_keeps_activated_components(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        replace = os.replace
        def commit_then_cancel(source, destination):
            replace(source, destination)
            raise KeyboardInterrupt()
        with patch.object(runtime_update.os, 'replace', side_effect=commit_then_cancel):
            with self.assertRaises(KeyboardInterrupt):
                self.rebuild()
        manifest = json.loads(self.manifest.read_text())
        host = self.pack / manifest['host']
        self.assertTrue(host.is_file())
        self.assertTrue(all((host.parent / name).is_file() for name in runtime_update.HOOKS))
        self.assert_preserved(manifest=False)

    def test_payload_revision_mismatch_fails_before_compilation(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        with patch.dict(os.environ, {'NX2A_PLAYER_API_REVISION': str(PLAYER_API_REVISION + 1)}):
            with self.assertRaisesRegex(ValueError, 'tools do not match'):
                self.rebuild()
        self.assert_preserved()

    def test_invalid_compiler_output_is_not_activated(self):
        fingerprint = self.profile(self.root / 'work' / f'{self.title}_build' / 'profile-derived')
        self.record(fingerprint)
        self.build.write_text(self.build.read_text() + "\n(out / 'libdmplay.so').write_bytes(b'invalid')\n")
        with self.assertRaisesRegex(ValueError, 'ARM64'):
            self.rebuild()
        self.assert_preserved()


if __name__ == '__main__':
    unittest.main()
