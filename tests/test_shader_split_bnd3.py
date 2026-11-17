"""shader_split.bnd3_members / bdt_shaders: FromSoftware's BND3 archives (DARK SOULS: REMASTERED's shaders are
GLSLC outputs in the BND3 archives of its dvdbnd archives: six DCX-compressed ones in dvdbnd0.bdt, 3,225 outputs).

A member's name is read from its name offset to its NUL terminator, and a name without a terminator runs to the
end of the archive. The read assigns the terminator's place and then asks whether it was found:
d[nm:(z := d.find(b'\0', nm)) if z >= 0 else len(d)] - the assignment sits in the branch whose condition tests
it, so the condition ran before the assignment and every archive raised UnboundLocalError on its first member.
Only DARK SOULS takes this path (the other games' archives have their own readers), so no other game's test saw
it: the port stopped in the shader split, and the log held the worker's traceback."""
import importlib.util, os, shutil, struct, sys, tempfile, unittest, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'nxnative/dm/gfx/tools'
sys.path.insert(0, str(TOOLS))   # the split imports the pool and the stage rules from beside it


def load():
    os.environ.setdefault('DM_GAME', '0100000000010000')   # the module asks for a game profile
    spec = importlib.util.spec_from_file_location('shader_split', TOOLS / 'shader_split.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def glslc_output(stage='ps'):
    """A GLSLC compiler output (the shaders themselves): the magic, the output's own length at +0x44, the control
    section (its magic, its own length at +0x20, the code area's length at +0x704) and the code area (the graphics
    magic, the SPH's stage bits in the word at +0x30)"""
    code = bytearray(0x40)
    code[0:4] = bytes.fromhex('78563412')                      # the graphics code area's magic
    struct.pack_into('<I', code, 0x30, {'vs': 1, 'ps': 5}[stage] << 10)   # the SPH's stage bits
    ctl = bytearray(0x880)
    ctl[0:4] = bytes.fromhex('34127698')                      # the control section's magic
    struct.pack_into('<I', ctl, 0x20, 0x880)                   # the control section's own length
    struct.pack_into('<I', ctl, 0x704, len(code))              # the code area's length
    out = bytearray(0x48) + ctl + code
    out[0:4] = b'\x91\x68\x86\x19'                             # the output's magic
    struct.pack_into('<I', out, 0x44, len(out))                # the output's own length
    return bytes(out)


def bnd3(members, fmt=0x5c):
    """A FromSoftware BND3 archive: 'BND3', the version, the format byte (0x5c, 64-bit offsets), the entry count
    at +0x10, then one 28-byte entry per member {u8 flags, 3 bytes 0, i32 length, u64 offset, i32 ID, u32 name
    offset, i32 unpacked length}, the members' bytes and the name table. The names are the bytes given: they
    carry their own NUL terminator (the last member's may be missing: the reader runs that name to the end of the
    archive then)"""
    hdr = bytearray(0x20)
    hdr[0:4] = b'BND3'
    hdr[12] = fmt
    struct.pack_into('<i', hdr, 0x10, len(members))
    base = 0x20 + 28 * len(members)
    data = b''.join(blob for _, blob in members)       # the members' bytes, then the name table
    names_at, entries, names, off = base + len(data), bytearray(), bytearray(), base
    for i, (name, blob) in enumerate(members):
        entries += struct.pack('<B3xiQiIi', 0, len(blob), off, i, names_at + len(names), len(blob))
        names += name
        off += len(blob)
    return bytes(hdr) + bytes(entries) + data + bytes(names)


def dcx(payload):
    """FromSoftware's DCX container (big-endian): 'DCX\\0', +0x18 'DCS\\0' u32 length, u32 stored length, +0x24
    'DCP\\0' + the method (DFLT: a zlib stream), +0x44 'DCA\\0' u32 8, then the stream"""
    z = zlib.compress(payload)
    hdr = bytearray(0x4c)
    hdr[0:4] = b'DCX\0'
    hdr[0x18:0x1c] = b'DCS\0'
    struct.pack_into('>II', hdr, 0x1c, len(payload), len(z))
    hdr[0x24:0x28] = b'DCP\0'
    hdr[0x28:0x2c] = b'DFLT'
    hdr[0x44:0x48] = b'DCA\0'
    struct.pack_into('>I', hdr, 0x48, 8)
    return bytes(hdr) + z


class Bnd3(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.S = load()
        cls.tmp = Path(tempfile.mkdtemp(prefix='nx2a-bnd3-'))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def members(self, d):
        return list(self.S.bnd3_members(d, '/t'))

    def test_the_member_names_are_read(self):
        """The names are Shift-JIS paths in the archive: the reader turns the backslashes into slashes and cuts
        the path before '/Source/'"""
        d = bnd3([(b'N:\\FRPG\\Source\\Shader\\a.vpo\0', b'first'),
                  (b'b.fpo\0', b'second')])
        self.assertEqual(self.members(d), [('Shader/a.vpo', b'first'), ('b.fpo', b'second')])

    def test_a_shift_jis_name_is_read(self):
        """The game's own paths are Shift-JIS: the name is decoded with it"""
        name = 'N:\\FRPG\\Source\\Shader\\シェーダー.vpo\0'.encode('shift-jis')
        self.assertEqual(self.members(bnd3([(name, b'x')])), [('Shader/シェーダー.vpo', b'x')])

    def test_a_name_without_a_terminator_runs_to_the_end(self):
        """The archive's last name may have no NUL: the name runs to the end of the archive, and the archive is
        read, not refused"""
        d = bnd3([(b'a.vpo\0', b'first'), (b'last.vpo', b'second')])
        self.assertEqual(self.members(d), [('a.vpo', b'first'), ('last.vpo', b'second')])

    def test_an_empty_archive_has_no_members(self):
        self.assertEqual(self.members(bnd3([])), [])

    def test_the_shaders_of_a_dcxbnd3_are_found(self):
        """DARK SOULS: REMASTERED's dvdbnd archives: the .bdt's entries are DCX-compressed BND3 archives, and
        the GLSLC outputs are their members"""
        out = []
        payload = dcx(bnd3([(b'N:\\FRPG\\Source\\Shader\\a.vpo\0', glslc_output('ps')),
                            (b'N:\\FRPG\\Source\\Shader\\b.vpo\0', glslc_output('vs'))]))
        p = self.tmp / 'dvdbnd0.bdt'
        p.write_bytes(payload)
        self.S.bdt_shaders(str(p), 'dvdbnd0.bdt', [(0x1234abcd, 0, len(payload))], out)
        self.assertEqual([st for _, st, _, _ in out], ['ps', 'vs'])
        self.assertEqual(out[0][0], '/dvdbnd0.bdt#1234abcd/Shader/a.vpo#GLSLC@0x0')
        self.assertEqual(out[0][3][:4], bytes.fromhex('78563412'))

    def test_the_archive_is_read_the_same_way_as_a_mapping_and_as_bytes(self):
        """The .bdt is mapped (it is 3.6 GB), so the reader sees a mapping where the desktop sees the bytes: the
        names come out the same either way"""
        d = bnd3([(b'N:\\FRPG\\Source\\Shader\\a.vpo\0', b'first'), (b'b.fpo\0', b'second')])
        p = self.tmp / 'dvdbnd1.bnd'
        p.write_bytes(d)
        with self.S.map_file(str(p)) as m:
            self.assertEqual(self.members(m), self.members(d))


if __name__ == '__main__':
    unittest.main()
