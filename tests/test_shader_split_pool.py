"""shader_split.py: the split runs over a RomFS that has files in it. The pool's work has to be picklable — a local function is not, and the split died with a PicklingError on
every desktop (the phone takes the forked path, where the child inherits the parent's memory and nothing is pickled)"""
import importlib.util, os, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'nxnative/dm/gfx/tools'


@unittest.skipUnless(importlib.util.find_spec('lz4'), 'no lz4')
class Pool(unittest.TestCase):
    """The split of a RomFS with a file in it"""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='nx2a-pool-'))
        (cls.tmp / 'romfs').mkdir()
        (cls.tmp / 'romfs' / 'plain.txt').write_bytes(b'no shaders here')
        (cls.tmp / 'main.nso').write_bytes(bytes(0x100))   # an empty NSO: no shaders in main

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_the_split_runs_over_a_romfs_with_files(self):
        out = self.tmp / 'out'
        out.mkdir()
        env = dict(os.environ, DM_GAME='0100000000010000')
        r = subprocess.run([sys.executable, str(TOOLS / 'shader_split.py'), str(self.tmp / 'romfs'), str(self.tmp / 'main.nso'),
                            str(out / 'pkg'), str(out / 'map.tsv'), str(out / 'facts.tsv'), str(out / 'pairs.tsv')],
                           env=env, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue((out / 'map.tsv').is_file())
