"""shader_split.py: the shaders' payloads go to a spill file instead of staying in memory (a phone's memory holds no 73,512 shaders), and the package it writes is the same as before"""
import hashlib, importlib.util, os, shutil, struct, subprocess, sys, tempfile, unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'nxnative/dm/gfx/tools'
CTL_MAGIC, GFX_MAGIC = bytes.fromhex('34127698'), bytes.fromhex('78563412')


def lslg(tag, code, ctl_len=0x880):
    """A decoded Switch shader (LSLG): the tag, the code area and the control section (the control's own length, its code area's length)"""
    ctl = bytearray(ctl_len)
    ctl[0:4] = CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, ctl_len)
    struct.pack_into('<I', ctl, 0x704, len(code))
    return b'LSLG' + tag + b'\0' * 6 + struct.pack('<I', len(code)) + code + struct.pack('<I', ctl_len) + bytes(ctl) + b'\0' * 24


def code_area(n, seed=0):
    c = bytearray(0x30 + 0x50 + n)
    c[0:4] = GFX_MAGIC
    for i in range(0x30, len(c), 4): c[i] = (i * 7) & 0xff
    struct.pack_into('<I', c, 0x30, 0x0461)   # the SPH word: shader type 1 (vertex)
    struct.pack_into('<I', c, 0x34, seed)      # every shader its own code
    return bytes(c)


def archive(shaders):
    """A version 2 shader code archive: the tables, then the code"""
    head = bytearray(struct.pack('<I', 2)) + struct.pack('<I', 0) * 2 + struct.pack('<I', 0)
    head += struct.pack('<I', len(shaders)); off = 0
    for rec, fr in shaders:
        head += struct.pack('<QIIB', off, len(rec), len(rec), fr); off += len(rec)
    head += struct.pack('<I', 0) + struct.pack('<I', 0)
    return bytes(head) + b''.join(r for r, _ in shaders)


def digest(folder):
    out = {}
    for p in sorted(folder.rglob('*')):
        if p.is_file() and 'payloads' not in p.parts: out[str(p.relative_to(folder))] = hashlib.sha256(p.read_bytes()).hexdigest()
    return out


@unittest.skipUnless(importlib.util.find_spec('lz4'), 'no lz4')
class Spill(unittest.TestCase):
    """The split of a small Unreal shader code archive, with the spill and without it"""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='nx2a-split-'))
        (cls.tmp / 'romfs').mkdir()
        (cls.tmp / 'ue_shaders').mkdir()
        (cls.tmp / 'ue_shaders' / 'ShaderArchive-Test.ushaderbytecode').write_bytes(
            archive([(lslg(b'SV', code_area(4096, i)), 0) for i in range(8)] + [(lslg(b'SP', code_area(2048, i)), 3) for i in range(4)]))
        (cls.tmp / 'main.nso').write_bytes(bytes(0x100))   # an empty NSO: no shaders in main

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def split(self, name, spill=True, budget=None):
        out = self.tmp / name
        if out.exists(): shutil.rmtree(out)
        out.mkdir()
        env = dict(os.environ, DM_GAME='0100000000010000', DM_UE_SHADERS=str(self.tmp / 'ue_shaders'))
        env.pop('DM_SHADER_SPILL', None)
        if not spill: env['DM_SHADER_SPILL'] = '0'
        if budget is not None: env['DM_SHADER_SPLILL_BUDGET_MB'] = str(budget)
        else: env.pop('DM_SHADER_SPLILL_BUDGET_MB', None)
        r = subprocess.run([sys.executable, str(TOOLS / 'shader_split.py'), str(self.tmp / 'romfs'), str(self.tmp / 'main.nso'),
                            str(out / 'pkg'), str(out / 'map.tsv'), str(out / 'facts.tsv'), str(out / 'pairs.tsv')],
                           env=env, capture_output=True, text=True)
        return r, out

    def test_the_package_is_the_same_with_and_without_the_spill(self):
        r1, a = self.split('a', spill=False)
        r2, b = self.split('b', spill=True)
        self.assertEqual(r1.returncode, 0, r1.stderr)
        self.assertEqual(r2.returncode, 0, r2.stderr)
        self.assertEqual(digest(a), digest(b))   # the package and the three tables, byte for byte

    def test_the_spill_file_holds_the_payloads(self):
        r, out = self.split('c')
        self.assertEqual(r.returncode, 0, r.stderr)
        spills = list((out / 'payloads').glob('payload-*.bin'))
        self.assertTrue(spills, 'the payloads did not go to the spill file')
        self.assertGreater(spills[0].stat().st_size, 0)
        self.assertTrue((out / 'pkg' / 'vs_other.bin').is_file())

    def test_over_the_budget_the_split_stops_with_an_error(self):
        r, out = self.split('d', budget=1)   # a budget of 1 MB: the split is over it at once
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('[RED]', r.stderr)
        self.assertIn('memory', r.stderr)


class Budget(unittest.TestCase):
    """The split's memory budget: a share of the device's memory, so a small phone stops the split with a clear error before the system kills it"""

    def load(self):
        os.environ.setdefault('DM_GAME', '0100000000010000')
        sys.path.insert(0, str(TOOLS))
        spec = importlib.util.spec_from_file_location('shader_split_budget', TOOLS / 'shader_split.py')
        m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        return m

    def setUp(self):
        self.s = self.load()
        self.old = self.s.BUDGET_MB

    def tearDown(self):
        self.s.BUDGET_MB = self.old

    def budget(self, total_mb, avail_mb=None):
        self.s.BUDGET_MB = 0
        with mock.patch.object(self.s, 'meminfo', return_value=(total_mb, avail_mb)):
            return self.s.budget_mb()

    def test_a_share_of_the_device_memory(self):
        self.assertEqual(self.budget(23672), 2048)   # a 24 GB phone: the cap
        self.assertEqual(self.budget(11500), 1437)   # a 12 GB phone: an eighth
        self.assertEqual(self.budget(3900), 487)     # a 4 GB phone
        self.assertEqual(self.budget(1000), 256)     # a tiny one: the floor
        self.assertEqual(self.budget(None), 1000)     # no /proc (a Mac): the default

    def test_the_environment_overrides_it(self):
        self.s.BUDGET_MB = 777
        self.assertEqual(self.s.budget_mb(), 777)

    def test_too_little_free_memory_stops_the_split(self):
        with mock.patch.object(self.s, 'meminfo', return_value=(23672, 120)):
            with self.assertRaises(SystemExit) as e:
                self.s.check_room()
            self.assertIn('[RED]', str(e.exception))
            self.assertIn('memory free', str(e.exception))

    def test_enough_free_memory_lets_the_split_run(self):
        with mock.patch.object(self.s, 'meminfo', return_value=(23672, 9000)):
            self.s.check_room()   # no error


if __name__ == '__main__':
    unittest.main()
