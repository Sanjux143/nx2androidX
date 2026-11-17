"""shader_split.py: a file that does not fit in the device's memory is mapped instead of refused (13 Sentinels' 4.2 GB
asset archive: the phone has no room for it, and the split stopped the port with a red line instead of reading it).
The mapping reads like the bytes it stands for: the readers slice it and look for magics in it."""
import importlib.util, mmap, os, shutil, struct, sys, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'nxnative/dm/gfx/tools'
sys.path.insert(0, str(TOOLS))   # the split imports the pool from beside it


def load():
    os.environ.setdefault('DM_GAME', '0100000000010000')   # the module asks for a game profile
    spec = importlib.util.spec_from_file_location('shader_split', TOOLS / 'shader_split.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def unity_bundle(name=b'a.bin', data=b'hello'):
    """A minimal UnityFS bundle: the header, the block info (stored) and the data. The info block is big-endian, as the reader reads it"""
    info = bytearray(16)                                    # the info block's first 16 bytes are skipped
    info += struct.pack('>i', 1)                            # one block
    info += struct.pack('>IIH', len(data), len(data), 0)    # its uncompressed size, its compressed size, its flags
    info += struct.pack('>i', 1)                            # one node
    info += struct.pack('>qqI', 0, len(data), 0)            # its offset, its size, its flags
    info += name + b'\0'
    head = b'UnityFS\0' + struct.pack('>I', 6) + b'5.x.x\0' + b'Unity\0'
    head += struct.pack('>qIII', 0, len(info), len(info), 0)  # the size, the info's size, the uncompressed size, the flags
    return head + bytes(info) + data


class Mapped(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.S = load()
        cls.tmp = Path(tempfile.mkdtemp(prefix='nx2a-map-'))
        (cls.tmp / 'blob.bin').write_bytes(b'HEAD' + b'SLCT' + bytes(100) + b'BNSH\0\0\0\0' + b'y' * 50)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_a_magic_is_found_in_a_mapping_and_in_the_bytes(self):
        b = (self.tmp / 'blob.bin').read_bytes()
        m = self.S.map_file(str(self.tmp / 'blob.bin'))
        self.assertTrue(self.S.has(b, b'SLCT'))
        self.assertTrue(self.S.has(m, b'SLCT'))
        self.assertTrue(self.S.has(m, b'BNSH\0\0\0\0'))
        self.assertFalse(self.S.has(m, b'NOPE'))
        self.assertEqual(len(b), len(m))

    def test_the_readers_give_the_same_answer_for_a_mapping_and_for_the_bytes(self):
        b = (self.tmp / 'blob.bin').read_bytes()
        m = self.S.map_file(str(self.tmp / 'blob.bin'))
        fb, fm = [], []
        self.S.scan_blob(b, 'x', fb); self.S.scan_blob(m, 'x', fm)
        self.assertEqual(fb, fm)

    def test_a_file_that_does_not_fit_is_mapped_not_refused(self):
        """The device's memory says there is no room for the file: it is mapped (its pages come from the file cache)"""
        self.S.meminfo = lambda: (16000, 1)      # 1 MB free: the file does not fit
        self.S.BUDGET_MB = 1
        self.S.SMALL_FILE = 1                   # the small files are read whole whatever the memory says
        try:
            big = self.tmp / 'big.bin'
            big.write_bytes(bytes(2 * 1024 * 1024))
            m = self.S.read_whole(str(big))
            self.assertEqual(len(m), 2 * 1024 * 1024)
            self.assertIsInstance(m, mmap.mmap, 'a mapping, not bytes')
        finally:
            del self.S.meminfo
            self.S.BUDGET_MB = 0
            self.S.SMALL_FILE = 64

    def test_a_zstd_frame_is_still_refused(self):
        """A frame is decompressed whole: it has to fit in memory"""
        self.S.meminfo = lambda: (16000, 1)
        self.S.BUDGET_MB = 1
        self.S.SMALL_FILE = 1
        try:
            big = self.tmp / 'big2.bin'
            big.write_bytes(bytes(2 * 1024 * 1024))
            with self.assertRaises(SystemExit) as e:
                self.S.read_whole(str(big), map_ok=False)
            self.assertIn('do not fit in this device', str(e.exception))
        finally:
            del self.S.meminfo
            self.S.BUDGET_MB = 0
            self.S.SMALL_FILE = 64

    def test_the_bundle_reader_works_on_a_mapping(self):
        """A mapped bundle (one too big for the device's memory): a reader that takes a whole buffer must not use a
        bytes-only method (index, count, split) on it, or it stops with a traceback where it used to stop with a red line"""
        import unityfs
        blob = unity_bundle()
        p = self.tmp / 'bundle.unity3d'
        p.write_bytes(blob)
        m = self.S.map_file(str(p))
        self.assertEqual(unityfs.bundle_files(m), unityfs.bundle_files(blob))
        self.assertEqual(unityfs.bundle_files(blob), [('a.bin', b'hello')])

    def test_the_common_string_table_works_on_a_mapping(self):
        import unityfs
        blob = b'AABB\0AnimationClip\0AnimationCurve\0foo\0bar\0'
        p = self.tmp / 'main.bin'
        p.write_bytes(blob)
        m = self.S.map_file(str(p))
        self.assertEqual(unityfs.common_strings(m), unityfs.common_strings(blob))
        self.assertTrue(unityfs.common_strings(blob))


if __name__ == '__main__':
    unittest.main()
