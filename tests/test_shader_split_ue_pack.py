"""shader_split.py: an Unreal modern pak (version 11) and the IoStore containers are not read whole (their shaders are in the shader code archives), a legacy pak still is, and a file
that does not fit in the device's memory is refused with a clear error instead of being read"""
import hashlib, importlib.util, os, shutil, struct, subprocess, sys, tempfile, unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'nxnative/dm/gfx/tools'
CTL_MAGIC, GFX_MAGIC = bytes.fromhex('34127698'), bytes.fromhex('78563412')
PAK_MAGIC = 0x5A6F12E1   # uepak.py: the magic in a pak's footer


def pak(version, size):
    """A pak file of the size bytes with the footer the reader looks for (the magic and the version): version 4's is 0x2c from the end, version 11's 0xc8"""
    d = bytearray(size)
    struct.pack_into('<II', d, size - (0x2c if version == 4 else 0xc8), PAK_MAGIC, version)
    return bytes(d)


class Counted:
    """A file that counts the bytes read from it"""

    def __init__(self, f, count):
        self.f = f; self.count = count

    def read(self, n=-1):
        d = self.f.read(n); self.count[0] += len(d); return d

    def __enter__(self): return self

    def __exit__(self, *a): self.f.close()

    def __getattr__(self, k): return getattr(self.f, k)


def counting_open(count, paths):
    """open that counts the bytes read from the given paths"""
    real = open

    def op(p, *a, **kw):
        f = real(p, *a, **kw)
        return Counted(f, count) if str(p) in paths else f

    return op


class ModernPak(unittest.TestCase):
    """The split of an Unreal modern pak or an IoStore container: the file is not read whole"""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='nx2a-uepak-'))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def setUp(self):
        os.environ.setdefault('DM_GAME', '0100000000010000')
        sys.path.insert(0, str(TOOLS))
        spec = importlib.util.spec_from_file_location('shader_split_uepak', TOOLS / 'shader_split.py')
        self.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.m)

    def scan(self, name, data, count=None):
        p = self.tmp / name
        p.write_bytes(data)
        if count is not None: self.m.open = counting_open(count, {str(p)})
        return self.m.scan_file((str(p), name))

    def test_a_modern_pak_is_not_read_whole(self):
        count = [0]
        rel, uniq, pairs, extra = self.scan('FFRS-Switch.pak', pak(11, 1 << 20), count)
        self.assertEqual((uniq, pairs, extra), ({}, set(), {}))   # no shaders: they are in the shader code archives
        self.assertLess(count[0], 4096)   # the footer only, not the file

    def test_an_iostore_container_is_not_read_whole(self):
        for name in ('FFRS-Switch.ucas', 'FFRS-Switch.utoc'):
            count = [0]
            rel, uniq, pairs, extra = self.scan(name, b'\0' * (1 << 20), count)
            self.assertEqual((uniq, pairs, extra), ({}, set(), {}))
            self.assertLess(count[0], 4096)   # the head only, not the file

    def test_a_legacy_pak_is_read_as_before(self):
        count = [0]
        rel, uniq, pairs, extra = self.scan('pakchunk0-Switch.pak', pak(4, 1 << 20), count)
        self.assertGreater(count[0], (1 << 19))   # the whole file: its shaders are inline in the cooked packages

    def test_a_file_that_does_not_fit_is_mapped_not_refused(self):
        """A file too big for this device's memory is mapped, not refused (its pages come from the file cache): the
        13 Sentinels' 4.2 GB asset archive stopped a phone's port before this"""
        p = self.tmp / 'big.bin'
        with open(p, 'wb') as f: f.truncate(20 << 20)   # 20 MB
        with mock.patch.object(self.m, 'meminfo', return_value=(4096, 10)), mock.patch.object(self.m, 'BUDGET_MB', 1):
            rel, uniq, pairs, extra = self.m.scan_file((str(p), 'big.bin'))
        self.assertEqual((uniq, pairs, extra), ({}, set(), {}))   # mapped and scanned: no shaders in it

    def test_a_file_that_fits_is_read(self):
        p = self.tmp / 'small.bin'
        with open(p, 'wb') as f: f.truncate(100 << 20)
        with mock.patch.object(self.m, 'meminfo', return_value=(23672, 9000)):
            rel, uniq, pairs, extra = self.m.scan_file((str(p), 'small.bin'))
        self.assertEqual((uniq, pairs, extra), ({}, set(), {}))   # read whole, and nothing found in it

    def test_without_the_device_memory_nothing_is_refused(self):
        p = self.tmp / 'huge.bin'
        with open(p, 'wb') as f: f.truncate(600 << 20)
        with mock.patch.object(self.m, 'meminfo', return_value=(None, None)):   # a Mac: no /proc
            rel, uniq, pairs, extra = self.m.scan_file((str(p), 'huge.bin'))
        self.assertEqual((uniq, pairs, extra), ({}, set(), {}))


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
    struct.pack_into('<I', c, 0x34, seed)
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
class Products(unittest.TestCase):
    """The split of a game with an Unreal modern pak: the package and the tables are the same as without it"""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='nx2a-uepak-prod-'))
        cls.romfs = cls.tmp / 'romfs'; cls.romfs.mkdir()
        (cls.tmp / 'ue_shaders').mkdir()
        (cls.tmp / 'ue_shaders' / 'ShaderArchive-Test.ushaderbytecode').write_bytes(
            archive([(lslg(b'SV', code_area(4096, i)), 0) for i in range(8)] + [(lslg(b'SP', code_area(2048, i)), 3) for i in range(4)]))
        (cls.tmp / 'main.nso').write_bytes(bytes(0x100))   # an empty NSO: no shaders in main

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def split(self, name):
        out = self.tmp / name
        out.mkdir()
        env = dict(os.environ, DM_GAME='0100000000010000', DM_UE_SHADERS=str(self.tmp / 'ue_shaders'))
        r = subprocess.run([sys.executable, str(TOOLS / 'shader_split.py'), str(self.romfs), str(self.tmp / 'main.nso'),
                            str(out / 'pkg'), str(out / 'map.tsv'), str(out / 'facts.tsv'), str(out / 'pairs.tsv')],
                           env=env, capture_output=True, text=True)
        return r, out

    def test_the_modern_pak_changes_nothing(self):
        r1, a = self.split('a')
        self.assertEqual(r1.returncode, 0, r1.stderr)
        (self.romfs / 'FFRS-Switch.pak').write_bytes(pak(11, 1 << 20))
        r2, b = self.split('b')
        self.assertEqual(r2.returncode, 0, r2.stderr)
        self.assertEqual(digest(a), digest(b))   # the package and the three tables, byte for byte
        self.assertIn('skipped FFRS-Switch.pak', r2.stdout)   # and the log says what it skipped


if __name__ == '__main__':
    unittest.main()
