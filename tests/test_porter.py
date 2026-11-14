# Unit tests of the port pipeline's own parts (python -m unittest discover tests). They need no game files, toolchains or device.
import contextlib, hashlib, importlib.util, io, json, os, random, re, shutil, struct, sys, tempfile, unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))
os.environ['NX2A_HOME'] = tempfile.mkdtemp(prefix='nx2a_test_home_')

from porter import config, datapack, dumps, pipeline, profiles   # noqa: E402
from porter.upstream import android_profile, game          # noqa: E402

# The pipeline's messages hold "→" (mem_profile's alias region line): stdout as porter.cli.main sets it, or the Windows release runner's cp1252 pipe fails the test
if hasattr(sys.stdout, 'reconfigure'): sys.stdout.reconfigure(encoding='utf-8', errors='replace')

NEO = '010043B013C5C000'


def profile_dir(rows, gid='0100000000AAA000'):
    """A throwaway game profile with an android.tsv of these rows, found through DM_GAMES_DIR"""
    root = Path(tempfile.mkdtemp(prefix='nx2a_test_profiles_')); d = root / gid; d.mkdir()
    (d / 'game.tsv').write_text(f'title_id\t{gid}\nprogram_id\t{gid}\nname\tTest Game\nipa\tTestGame.ipa\nmodules\tmain sdk\n', encoding='utf-8')
    (d / 'android.tsv').write_text(''.join(f'{k}\t{v}\n' for k, v in rows), encoding='utf-8')
    os.environ['DM_GAMES_DIR'] = str(root)
    return d


class AndroidProfiles(unittest.TestCase):
    def tearDown(self): os.environ.pop('DM_GAMES_DIR', None)

    def test_neo(self):
        p = profiles.load(NEO)
        self.assertEqual(p.status, 'playable'); self.assertEqual(p.package, 'org.nx2a.neo'); self.assertTrue(p.landscape)
        self.assertEqual(p.settings, {'docked': '0', 'overlay': '1', 'rumble': '1'}); self.assertEqual(p.requires, ['bcn'])

    def test_only_android_profiles_listed(self):
        rows = profiles.all_entries()
        self.assertIn(NEO, [r.id for r in rows]); self.assertTrue(all(r.android for r in rows))
        self.assertGreater(len(profiles.all_entries(android_only=False)), len(rows))

    def test_fps_setting(self):   # the pause menu's Show FPS (front_player.c settings_load fps=)
        d = profile_dir([('status', 'boots'), ('setting', 'fps=1')])
        self.assertEqual(android_profile.load(d.name).settings, {'fps': '1'})
        with self.assertRaises(android_profile.ProfileError): android_profile.load(profile_dir([('status', 'boots'), ('setting', 'fps=2')]).name)

    def test_opacity_setting(self):   # the gamepad's opacity, 20..80 percent by 5 (the menu's slider, overlay_layout.h OV_OPACITY_MIN..MAX)
        self.assertEqual(android_profile.load(profile_dir([('status', 'boots'), ('setting', 'opacity=45')]).name).settings, {'opacity': '45'})
        for v in ('10', '85', '42'):
            with self.assertRaises(android_profile.ProfileError, msg=v): android_profile.load(profile_dir([('status', 'boots'), ('setting', f'opacity={v}')]).name)

    def test_default_package(self):
        d = profile_dir([('status', 'boots')])
        self.assertEqual(android_profile.load(d.name).package, 'org.nx2a.t0100000000aaa000')

    def test_bad_keys_are_refused(self):
        for rows in ([('status', 'great')], [('colour', 'red')], [('setting', 'volume=3')], [('overlay', 'tablet q 0.1 0.2')], [('env', 'lower=1')], [('package', 'Not A Package')],
                     [('status', 'boots'), ('status', 'playable')]):
            d = profile_dir(rows)
            with self.assertRaises(android_profile.ProfileError, msg=rows): android_profile.load(d.name)

    def test_header_default_layout(self):   # the ergonomic layouts (the themes' design), the tablet's then the phone's, the same controls in each
        h = android_profile.header(android_profile.load(NEO))
        rx = r'\{(OV_\w+), (-?[\d.]+)f, (-?[\d.]+)f, ([\d.]+)f, ([\d.]+)f, ([\d.]+)f, (\w+), "([^"]*)", "(\w+)"\}'
        tablet, phone = (re.findall(rx, h.split(f'#define DM_AOV_{n}')[1].split('#define')[0]) for n in ('TABLET', 'PHONE'))
        self.assertEqual((len(tablet), len(phone)), (16, 16)); self.assertIn('#define DM_AOV_N 16', h)
        self.assertEqual([r[0] for r in tablet], [r[0] for r in phone]); self.assertEqual([r[6] for r in tablet], [r[6] for r in phone])
        self.assertEqual(tablet[0], ('OV_STICK_L', '0.0', '0.2105', '0.5789', '0.2421', '0.2421', '0', '', 'stick_l'))   # 184 px of the 1216x760 design, 160 px from the left edge
        a = next(r for r in tablet if r[6] == 'DM_BTN_A'); self.assertEqual(a[1:3], ('1.0', '-0.1421'))   # from the right edge
        self.assertEqual(next(r for r in tablet if r[6] == 'DM_BTN_MINUS')[-2], '-')   # the minus button's label, not "no label"

    def test_header_overlay_move(self):   # a moved control: x and y fractions of the window, in the one layout named
        d = profile_dir([('status', 'boots'), ('overlay', 'phone a 0.5 0.5')])
        h = android_profile.header(android_profile.load(d.name))
        self.assertIn('{OV_BUTTON, 0.5f, 0.0f, 0.5f, 0.1456f, 0.1456f, DM_BTN_A, "A", "a"}', h.split('#define DM_AOV_PHONE')[1])
        self.assertNotIn('0.5f, 0.0f, 0.5f', h.split('#define DM_AOV_PHONE')[0])
        with self.assertRaises(android_profile.ProfileError): android_profile.load(profile_dir([('status', 'boots'), ('overlay', 'a 0.5 0.5')]).name)

    def test_theme_mapping(self):   # porter packs the game's own pack, the ergo pack for a game the table does not name
        self.assertEqual(android_profile.theme(NEO).name, 'neotwewy'); self.assertEqual(android_profile.theme(NEO.lower()).name, 'neotwewy')
        self.assertEqual(android_profile.theme('0100000000AAA000').name, 'ergo')
        rows = [l.split('\t') for l in (android_profile.THEMES / 'themes.tsv').read_text(encoding='utf-8').splitlines() if l and not l.startswith('#')]
        self.assertEqual(len({r[0] for r in rows}), len(rows))   # one pack per game
        for r in rows: self.assertRegex(r[0], r'^0100[0-9A-F]{12}$'); self.assertTrue((android_profile.THEMES / r[1] / 'theme.tsv').is_file(), r)

    def test_theme_packs(self):   # every pack: both layouts' atlases, and sprites only of the layout's controls, within the atlas, every control drawn idle (a button held too)
        from PIL import Image
        names = {r['name'] for r in android_profile.default_layout()}
        for pack in sorted(p for p in android_profile.THEMES.iterdir() if p.is_dir()):
            rows = [l.split('\t') for l in (pack / 'theme.tsv').read_text(encoding='utf-8').splitlines() if l and not l.startswith('#')]
            lays = {r[1]: r for r in rows if r[0] == 'layout'}
            self.assertEqual(set(lays), set(android_profile.LAYOUTS), pack.name)
            for lay, row in lays.items():
                im = Image.open(pack / row[4]); self.assertIn(im.mode, ('P', 'RGBA'), pack.name)   # what overlay_theme.c decodes
                sp = [r for r in rows if r[0] == 'sprite' and r[1] == lay]
                for r in sp:
                    self.assertIn(r[2], names, r); self.assertIn(r[4], ('idle', 'held', 'knob', 'decor'), r)
                    x, y, w, h = map(int, r[5:9]); self.assertTrue(x >= 0 and y >= 0 and x + w <= im.width and y + h <= im.height, (pack.name, r))
                drawn = {(r[2], r[3], r[4]) for r in sp}
                for n in names - {'dpad'}:
                    self.assertIn((n, '-', 'idle'), drawn, (pack.name, lay, n))
                    if n not in ('stick_l', 'stick_r'): self.assertIn((n, '-', 'held'), drawn, (pack.name, lay, n))
                for d in ('up', 'down', 'left', 'right'): self.assertTrue({('dpad', d, 'idle'), ('dpad', d, 'held')} <= drawn, (pack.name, lay, d))
                self.assertTrue({('stick_l', '-', 'knob'), ('stick_r', '-', 'knob')} <= drawn, (pack.name, lay))

    def test_apk_assets(self):   # mkapk.py --asset: the theme's files as assets/overlay/<name>, nothing of the other packs
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_apk_'))
        try:
            so = t / 'libdmhost.so'; so.write_bytes(b'\x7fELF' + b'\0' * 60); pack = android_profile.theme(NEO)
            args = [a for f in ('theme.tsv', 'tablet.png', 'phone.png') for a in ('--asset', f'overlay/{f}={pack / f}')]
            import subprocess, zipfile
            subprocess.run([sys.executable, str(REPO / 'nxnative/dm/android/apk/mkapk.py'), str(so), str(t / 'a.apk'), str(t / 'k.json'), '--lib', 'dmhost'] + args,
                           check=True, capture_output=True)
            with zipfile.ZipFile(t / 'a.apk') as z:
                self.assertEqual(sorted(n for n in z.namelist() if n.startswith('assets/')), ['assets/overlay/phone.png', 'assets/overlay/tablet.png', 'assets/overlay/theme.tsv'])
                self.assertEqual(z.read('assets/overlay/tablet.png'), (pack / 'tablet.png').read_bytes())
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_apk_adaptive_icon(self):   # mkapk.py --icon-fg/--icon-bg: an anydpi-v26 adaptive icon beside the plain one, its layers as drawables 1 and 2
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_apk_'))
        try:
            so = t / 'libdmhost.so'; so.write_bytes(b'\x7fELF' + b'\0' * 60)
            for n in ('icon', 'fg', 'bg'): (t / f'{n}.png').write_bytes(n.encode())
            import subprocess, zipfile
            subprocess.run([sys.executable, str(REPO / 'nxnative/dm/android/apk/mkapk.py'), str(so), str(t / 'a.apk'), str(t / 'k.json'), '--lib', 'dmhost',
                            '--icon', str(t / 'icon.png'), '--icon-fg', str(t / 'fg.png'), '--icon-bg', str(t / 'bg.png')], check=True, capture_output=True)
            with zipfile.ZipFile(t / 'a.apk') as z:
                self.assertEqual(z.read('res/drawable-nodpi-v4/icon_fg.png'), b'fg'); self.assertEqual(z.read('res/drawable-nodpi-v4/icon_bg.png'), b'bg')
                xml, arsc = z.read('res/drawable-anydpi-v26/icon.xml'), z.read('resources.arsc')
                self.assertIn('adaptive-icon'.encode('utf-16-le'), xml)
                for ref in (0x7f010001, 0x7f010002): self.assertIn(struct.pack('<I', ref), xml)   # background and foreground point at icon_bg and icon_fg
                for s in ('icon_fg', 'icon_bg', 'res/drawable-anydpi-v26/icon.xml'): self.assertIn(s.encode('utf-16-le'), arsc)
            bad = subprocess.run([sys.executable, str(REPO / 'nxnative/dm/android/apk/mkapk.py'), str(so), str(t / 'b.apk'), str(t / 'k.json'), '--icon', str(t / 'icon.png'),
                                  '--icon-fg', str(t / 'fg.png')], capture_output=True)
            self.assertNotEqual(bad.returncode, 0)
        finally:
            shutil.rmtree(t, ignore_errors=True)


class DataPack(unittest.TestCase):
    def test_build(self):
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            (t / 'patched').mkdir(); (t / 'patched/main.patched.bin').write_bytes(b'x' * 10)
            (t / 'romfs/Data').mkdir(parents=True); (t / 'romfs/Data/a.bin').write_bytes(b'y' * 20)
            (t / 'aoc/1').mkdir(parents=True); (t / 'aoc/1/d.bin').write_bytes(b'z')
            (t / 'shaders.dmsv').write_bytes(b'DMSV')
            p = profiles.load(NEO)
            man = datapack.build(t / 'out-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv',
                                 aoc=t / 'aoc', language='ja')
            o = t / 'out-data'
            self.assertTrue((o / 'mods/main.patched.bin').is_file()); self.assertTrue((o / 'RomFS/Data/a.bin').is_file()); self.assertTrue((o / 'aoc/1/d.bin').is_file())
            env = (o / 'env.txt').read_text()
            self.assertIn('DM_LANG=ja', env); self.assertIn(f'DM_AOC_DIR={t / "out-data"}/aoc', env)   # the pack's own folder
            self.assertEqual((o / 'settings.txt').read_text(), 'docked=0\noverlay=1\nrumble=1\n')
            self.assertEqual((man['files'], man['bytes'], man['dlc'], man['requires']), (4, 35, [1], ['bcn']))   # the installer checks requires
            self.assertEqual(man['status'], p.status)   # the app tells the player what to expect (an experimental port says so)
            self.assertEqual(datapack.read_manifest(o)['package'], 'org.nx2a.neo')
            datapack.build(t / 'touch-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv',
                           settings=pipeline.TOUCH_SETTINGS)   # --touch: the gamepad starts hidden, the profile's other settings stay
            self.assertEqual((t / 'touch-data/settings.txt').read_text(), 'docked=0\noverlay=0\nrumble=1\n')
            bare = android_profile.load(profile_dir([('status', 'experimental')]).name); os.environ.pop('DM_GAMES_DIR', None)   # no setting rows: --touch alone still writes the file
            datapack.build(t / 'bare-data', profile=bare, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv', settings=pipeline.TOUCH_SETTINGS)
            self.assertEqual((t / 'bare-data/settings.txt').read_text(), 'overlay=0\n')
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_the_manifest_records_player_api_revision(self):
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            (t / 'patched').mkdir(); (t / 'patched/main.patched.bin').write_bytes(b'x' * 10)
            (t / 'romfs').mkdir(); (t / 'shaders.dmsv').write_bytes(b'DMSV')
            p = profiles.load(NEO)
            man = datapack.build(t / 'out-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv')
            self.assertEqual(man['player_api_revision'], datapack.PLAYER_API_REVISION)
            self.assertEqual(datapack.read_manifest(t / 'out-data')['player_api_revision'], datapack.PLAYER_API_REVISION)
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_the_exported_saves_survive_a_rebuild(self):   # the pack is rebuilt whole: the player's exported backups are put aside and put back
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            (t / 'patched').mkdir(); (t / 'patched/main.patched.bin').write_bytes(b'x' * 10)
            (t / 'romfs').mkdir(); (t / 'shaders.dmsv').write_bytes(b'DMSV')
            p = profiles.load(NEO)
            o = t / 'out-data'
            datapack.build(o, profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv')
            (o / 'saves').mkdir(); (o / 'saves' / '20261110-120000').mkdir(); (o / 'saves' / '20261110-120000' / 'save.dat').write_bytes(b'SAVE')
            datapack.build(o, profile=p, version='v196609', display='1.04', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv')
            self.assertTrue((o / 'saves' / '20261110-120000' / 'save.dat').is_file())   # the backup is still there
            self.assertFalse((t / 'out-data.saves-keep').exists())                     # and the side folder is gone
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_app_mode_pack(self):   # --app: the pack carries what the APK used to (host, drivers, icon, the gamepad's theme), named in the manifest
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            (t / 'patched').mkdir(); (t / 'patched/main.patched.bin').write_bytes(b'x' * 10)
            (t / 'romfs/Data').mkdir(parents=True); (t / 'romfs/Data/a.bin').write_bytes(b'y' * 20)
            (t / 'shaders.dmsv').write_bytes(b'DMSV')
            (t / 'libdmplay.so').write_bytes(b'ELF'); (t / 'libvulkan_freedreno.so').write_bytes(b'DRV'); (t / 'libadrenotools.so').write_bytes(b'AT')
            (t / 'theme.tsv').write_text('theme'); (t / 'icon.png').write_bytes(b'PNG')
            p = profiles.load(NEO)
            man = datapack.build(t / 'out-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv',
                                 files={'host/libdmplay.so': t / 'libdmplay.so', 'drivers/libvulkan_freedreno.so': t / 'libvulkan_freedreno.so',
                                        'drivers/libadrenotools.so': t / 'libadrenotools.so', 'overlay/theme.tsv': t / 'theme.tsv', 'icon.png': t / 'icon.png'},
                                 host='host/libdmplay.so', icon='icon.png', drivers=['drivers/libadrenotools.so', 'drivers/libvulkan_freedreno.so'])
            o = t / 'out-data'
            self.assertTrue((o / 'host/libdmplay.so').is_file()); self.assertTrue((o / 'drivers/libvulkan_freedreno.so').is_file())   # subfolders are made
            self.assertTrue((o / 'overlay/theme.tsv').is_file()); self.assertTrue((o / 'icon.png').is_file())
            man = datapack.read_manifest(o)
            self.assertEqual(man['host'], 'host/libdmplay.so'); self.assertEqual(man['icon'], 'icon.png')
            self.assertEqual(man['drivers'], ['drivers/libadrenotools.so', 'drivers/libvulkan_freedreno.so'])
            datapack.build(t / 'pc-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv')
            pc = datapack.read_manifest(t / 'pc-data')   # the PC flow's pack carries none of the app's fields
            self.assertNotIn('host', pc); self.assertNotIn('icon', pc); self.assertNotIn('drivers', pc)
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_the_pack_can_point_at_the_romfs_outside(self):   # the app's mode: the pack holds no RomFS, it points at the build folder's own (the same game twice on the phone is too much)
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            (t / 'patched').mkdir(); (t / 'patched/main.patched.bin').write_bytes(b'x' * 10)
            (t / 'romfs/Data').mkdir(parents=True); (t / 'romfs/Data/a.bin').write_bytes(b'y' * 20)
            (t / 'shaders.dmsv').write_bytes(b'DMSV')
            p = profiles.load(NEO)
            man = datapack.build(t / 'out-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv',
                                 romfs_at=str(t / 'romfs'))
            o = t / 'out-data'
            self.assertFalse((o / 'RomFS').exists())                       # the pack does not carry the game's files
            self.assertIn(f'DM_ROMFS={t / "romfs"}', (o / 'env.txt').read_text())   # the host reads them from there
            self.assertEqual(man['romfs_at'], str(t / 'romfs'))            # the app checks that folder before the game runs
            self.assertEqual(datapack.read_manifest(o)['romfs_at'], str(t / 'romfs'))
            self.assertNotIn('romfs_at', datapack.build(t / 'pc-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv'))   # the PC flow's pack carries its own RomFS
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_the_app_pack_carries_no_game_profile(self):   # the profile lives in the workspace or in the payload: the pack carries no copy of it
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            port = pipeline.Port(pipeline.Options(t))
            port.g = game.load(NEO); port.app_name = 'NEO'; port.work_root = t / 'work'; port.o.app = True; port.o.out = t
            port.so = t / 'libdmplay.so'; port.so.write_bytes(b'ELF')
            port.game = t / 'game'; (port.game / 'RomFS').mkdir(parents=True)
            port.fps_files = {}; port.fps_env = []; port.ap = None; port.pkg_ver = 'v196608'; port.display = '1.03'
            port.patched = t / 'patched'; port.patched.mkdir(); port.dmsv = None; port.aoc = None; port.sysdata = []
            port.lang = 'en-US'; port.package = 'org.nx2a.test'
            captured = {}
            def build(dst, **kw):
                captured.update(kw); return {'files': 1, 'bytes': 1}
            with mock.patch.object(pipeline.datapack, 'build', side_effect=build), \
                 mock.patch.object(port, 'button_font', return_value=([], {})), \
                 mock.patch.object(port, 'shared_font', return_value=([], {})), \
                 mock.patch.object(port, 'theme_files', return_value={}), \
                 mock.patch.object(port, 'icon_png', return_value=None), \
                 mock.patch.object(port, 'drivers', return_value=[]), \
                 mock.patch.object(port, 'loader_libs', return_value=[]), \
                 mock.patch.object(port, 'lists', return_value={}):
                port.data()
            self.assertEqual([name for name in captured['files'] if name.startswith('profile/')], [])
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_a_leftover_half_made_pack(self):   # a port killed mid-way leaves one behind: it must not stop the next port (the log of 2026-11-09: FileNotFoundError, then exit 139)
        from unittest import mock
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            (t / 'patched').mkdir(); (t / 'patched/main.patched.bin').write_bytes(b'x' * 10)
            (t / 'romfs/Data').mkdir(parents=True); (t / 'romfs/Data/a.bin').write_bytes(b'y' * 20)
            (t / 'shaders.dmsv').write_bytes(b'DMSV')
            (t / 'out-data.partial').mkdir()   # the half-made pack of the port that was killed
            real = shutil.rmtree
            def vanishing(p, *a, **k):
                real(p, *a, **k)      # the tree goes
                real(p, *a, **k)      # and the folder is gone when the next sweep comes: the port must not mind
            p = profiles.load(NEO)
            with mock.patch.object(shutil, 'rmtree', vanishing):
                datapack.build(t / 'out-data', profile=p, version='v196608', display='1.03', patched=t / 'patched', romfs=t / 'romfs', shaders=t / 'shaders.dmsv')
            self.assertTrue((t / 'out-data' / datapack.MANIFEST).is_file()); self.assertFalse((t / 'out-data.partial').exists())
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_the_pack_s_place(self):   # the app's mode: the pack goes to the app's own folder, beside the build folders (the same volume, so the pack hard links the build's files); the desktop's mode: the output folder
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_pack_'))
        try:
            port = pipeline.Port(pipeline.Options(t))
            port.g = game.load(NEO); port.app_name = 'NEO'; port.work_root = t / 'work'; port.o.out = t
            stem = pipeline.app_stem(port.g, port.app_name)
            self.assertEqual(port.data_dir(), t / f'{stem}-data')                 # the desktop: the output folder
            port.o.app = True
            self.assertEqual(port.data_dir(), t / 'ports' / f'{stem}-data')      # the app: the app's own folder
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_the_pack_can_go_to_the_removable_card(self):   # the app may put the pack on the card (NX2A_PACK_DIR): the build stays on the phone
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_card_'))
        old = os.environ.get('NX2A_PACK_DIR')
        try:
            port = pipeline.Port(pipeline.Options(t))
            port.g = game.load(NEO); port.app_name = 'NEO'; port.work_root = t / 'work'; port.o.out = t; port.o.app = True
            stem = pipeline.app_stem(port.g, port.app_name)
            card = t / 'card/ports'
            os.environ['NX2A_PACK_DIR'] = str(card)
            self.assertEqual(port.data_dir(), card / f'{stem}-data')             # the card: the app's own folder there
            self.assertEqual(port.pack_dir_root(), card)
            os.environ.pop('NX2A_PACK_DIR')
            self.assertEqual(port.data_dir(), t / 'ports' / f'{stem}-data')      # without it: the phone's own storage
        finally:
            if old is None: os.environ.pop('NX2A_PACK_DIR', None)
            else: os.environ['NX2A_PACK_DIR'] = old
            shutil.rmtree(t, ignore_errors=True)

    def test_the_loader_is_the_app_s_own(self):   # the host dlopens the loader by name: only the app's native library folder is on the linker's path, so the loader lives there and the pack carries the hooks
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_loader_'))
        try:
            port = pipeline.Port(pipeline.Options(t))
            (t / pipeline.ADRENOTOOLS_LOADER).write_bytes(b'AT')
            for n in pipeline.ADRENOTOOLS_HOOKS: (t / n).write_bytes(b'HOOK')
            port.so = t / 'libdmplay.so'; port.so.write_bytes(b'ELF')
            hooks = sorted(p.name for p in port.loader_libs())
            self.assertNotIn(pipeline.ADRENOTOOLS_LOADER, hooks)          # the loader is not the pack's
            self.assertEqual(sorted(pipeline.ADRENOTOOLS_HOOKS), hooks)    # the hooks travel with the host
            self.assertEqual([pipeline.ADRENOTOOLS_LOADER], [p.name for p in port.loader_so()])
        finally:
            shutil.rmtree(t, ignore_errors=True)

    @unittest.skipUnless(any(Path(p).is_file() for p in pipeline.LETTER_FONTS), 'no letter font on this machine')
    def test_button_font(self):   # the Nintendo extension stand-in: made once, in the pack, named in env.txt (Super Mario Bros. Wonder's "Press L+R" drew '?' without it)
        try: from fontTools.ttLib import TTFont
        except ImportError: self.skipTest('no fontTools')
        from types import SimpleNamespace
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_font_'))
        try:
            port = SimpleNamespace(aw=t / 'android', logs=t / 'logs', env=dict(os.environ, PYTHONUTF8='1'), package='org.nx2a.test',
                                   data_dir=lambda: t / 'out-data')
            env, files = pipeline.Port.button_font(port)
            self.assertEqual(env, [f'DM_SHARED_FONT_EXT={t / "out-data"}/nintendo_ext.ttf']); self.assertEqual(list(files), ['nintendo_ext.ttf'])
            cmap = TTFont(files['nintendo_ext.ttf']).getBestCmap()
            self.assertTrue({0xE0E0, 0xE0E1, 0xE0E4, 0xE0E5, 0xE0EF, 0xE100} <= set(cmap))   # A, B, L, R, +, stick
            made = files['nintendo_ext.ttf'].stat().st_mtime_ns
            self.assertEqual(pipeline.Port.button_font(port)[1]['nintendo_ext.ttf'].stat().st_mtime_ns, made)   # reused while the tool and the letters are unchanged
        finally:
            shutil.rmtree(t, ignore_errors=True)

    def test_the_shared_font(self):   # the game's Chinese text: the shared font is the player's own or the one the tools ship (the shape a game that parses it expects)
        try: from fontTools.ttLib import TTFont
        except ImportError: self.skipTest('no fontTools')
        from types import SimpleNamespace
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_cjk_'))
        try:
            port = SimpleNamespace(o=SimpleNamespace(shared_font=None, app=False), package='org.nx2a.test',
                                   data_dir=lambda: t / 'out-data')
            env, files = pipeline.Port.shared_font(port)
            self.assertEqual(env, [f'DM_SHARED_FONT={datapack.files_folder("org.nx2a.test")}/standard.ttf'])   # the desktop: the install puts the pack in the app's files folder
            self.assertEqual(list(files), ['standard.ttf'])
            f = TTFont(files['standard.ttf'])
            cmap = f.getBestCmap()
            self.assertTrue({0x4E00, 0x4E2D, 0x6587} <= set(cmap))   # three CJK code points
            self.assertIn(0x549A, cmap)                              # a GB2312 level 2 character (the game's title has one: it drew as '?')
            self.assertIn(ord('東'), cmap)                            # a traditional character (GB2312 is simplified only: a traditional game drew it as '?')
            self.assertIn(ord('咚'), cmap)                            # the game's own title character
            self.assertTrue({0x41, 0x61, 0x30} <= set(cmap))         # A, a, 0
            self.assertEqual(f['post'].formatType, 2.0)             # the glyph names, which the game builds its glyph table from
            self.assertEqual(f['head'].unitsPerEm, 2048)
            # the app's mode: the shipped font stays where it lies (the payload), so the pack does not carry it
            app = SimpleNamespace(o=SimpleNamespace(shared_font=None, app=True), package='org.nx2a.test', data_dir=lambda: t / 'out-data')
            env, files = pipeline.Port.shared_font(app)
            self.assertEqual(env, [f'DM_SHARED_FONT={pipeline.SHARED_CJK}']); self.assertEqual(files, {})
            # the player's own font wins: it goes in the pack, and the host reads it from there
            mine = t / 'mine.ttf'; shutil.copyfile(pipeline.SHARED_CJK, mine)
            own = SimpleNamespace(o=SimpleNamespace(shared_font=mine, app=True), package='org.nx2a.test', data_dir=lambda: t / 'out-data')
            env, files = pipeline.Port.shared_font(own)
            self.assertEqual(env, [f'DM_SHARED_FONT={t / "out-data"}/standard.ttf']); self.assertEqual(files, {'standard.ttf': mine})
        finally:
            shutil.rmtree(t, ignore_errors=True)


class Experimental(unittest.TestCase):
    def test_ios_profile_copied_with_experimental_android_tsv(self):   # an iOS-only profile is copied out of the repository, never edited in place
        ios_only = '010099C022B96000'   # Super Mario Galaxy: an iOS profile without android.tsv or patches.py (it renders with Vulkan itself, which the Android host does not take)
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_exp_')); environ = dict(os.environ)
        try:
            port = pipeline.Port(pipeline.Options(folder=t, experimental=True)); port.env = {}; port.work = t / f'{ios_only}_build'; port.o.keys = None
            g, ap = port.experimental_profile(game.load(ios_only), ios_only)
            self.assertEqual(g.dir, (t / f'{ios_only}_build/profile/{ios_only}').resolve())
            self.assertEqual((ap.status, ap.package), ('experimental', f'org.nx2a.t{ios_only.lower()}'))
            self.assertFalse((REPO / f'nxnative/dm/games/{ios_only}/android.tsv').exists())
            self.assertFalse((g.dir / 'patches.py').exists())   # patch_nso.py finds the per-build cuts in dm/games
            self.assertEqual(os.environ.get('DM_GAMES_FIRST'), '1')
        finally:
            os.environ.clear(); os.environ.update(environ); shutil.rmtree(t, ignore_errors=True)

    @staticmethod
    def _npdm(exefs, flags):   # a main.npdm: META magic, the flags byte (bit 0 Is64BitInstruction) at 0xC
        exefs.mkdir(parents=True, exist_ok=True); d = bytearray(0x80); d[:4] = b'META'; d[0xC] = flags; (exefs / 'main.npdm').write_bytes(bytes(d))

    def test_32bit_game_refused(self):   # issue #161: Rune Factory 4 Special is AArch32 (flags 0x0); every 64-bit game has bit 0 set (0x27, 0x37)
        from porter.upstream import BuildError, nsp
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_32_'))
        try:
            nsp.require_64bit(t, 'no npdm')   # no main.npdm: nothing to judge, the run goes on
            self._npdm(t, 0x37); nsp.require_64bit(t, 'a 64-bit game')
            self._npdm(t, 0x00)
            with self.assertRaisesRegex(BuildError, r'32-bit \(AArch32\)'): nsp.require_64bit(t, 'Rune Factory 4 Special')
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_32bit_reused_provisional_profile_refused(self):   # a provisional profile an older porter wrote for a 32-bit game is not reused
        from porter.upstream import BuildError
        tid = '010051D00E3A4000'; t = Path(tempfile.mkdtemp(prefix='nx2a_test_32_')); environ = dict(os.environ)
        try:
            d = t / f'{tid}_build/profile/{tid}'; d.mkdir(parents=True); (d / 'game.tsv').write_text(f'title_id\t{tid}\n')
            self._npdm(t / f'{tid}_survey/extract/ExeFS', 0x00)
            port = pipeline.Port(pipeline.Options(folder=t, experimental=True)); port.env = {}; port.work = t / f'{tid}_build'
            with self.assertRaisesRegex(BuildError, '32-bit'): port.experimental_profile(None, tid)
        finally:
            os.environ.clear(); os.environ.update(environ); shutil.rmtree(t, ignore_errors=True)

    def test_dynsyms_elf32_is_named(self):   # an AArch32 module's ELF32 dynamic entries read as 64-bit ones have no DT_SYMTAB: a ValueError that says so, not KeyError: 6
        sys.path.insert(0, str(REPO / 'nxnative/dm/tools')); import nso_syms
        img = bytearray(0x400); struct.pack_into('<I', img, 4, 0x100); img[0x100:0x104] = b'MOD0'; struct.pack_into('<i', img, 0x104, 0x100)
        for i, (tag, v) in enumerate([(1, 1), (1, 0x4b), (0x15, 0), (6, 0x300), (5, 0x340), (0, 0)]): struct.pack_into('<II', img, 0x200 + i * 8, tag, v)   # Elf32_Dyn
        with self.assertRaisesRegex(ValueError, 'not a 64-bit NSO'): nso_syms.dynsyms(bytes(img))

    def test_crash_log(self):   # an error porter does not expect: the traceback goes to <build folder>/logs/crash.log, and the error is raised again
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_crash_'))
        try:
            port = pipeline.Port.__new__(pipeline.Port); port.work = t / 'X_build'
            def boom(): return {}[6]
            port._run = boom
            with self.assertRaises(KeyError): port.run()
            self.assertIn('KeyError: 6', (t / 'X_build/logs/crash.log').read_text(encoding='utf-8'))
        finally: shutil.rmtree(t, ignore_errors=True)


class Misc(unittest.TestCase):
    def test_config_roundtrip(self):
        config.set_value('device', 'abc:5555'); self.assertEqual(config.load()['device'], 'abc:5555')
        with self.assertRaises(KeyError): config.set_value('nope', 'x')

    def test_version_code_grows_with_updates(self):
        self.assertLess(pipeline.version_code('v0'), pipeline.version_code('v65536'))
        self.assertEqual(pipeline.version_code('v196608'), 196609)

    def test_version_code_offset(self):   # a rebuild lifted above an earlier one, still below the next game version's port
        self.assertEqual(pipeline.version_code('v589824', 1), 589826)
        self.assertLess(pipeline.version_code('v65536', pipeline.VERCODE_OFFSET_MAX), pipeline.version_code('v131072'))
        for bad in (-1, pipeline.VERCODE_OFFSET_MAX + 1):
            with self.assertRaises(pipeline.BuildError): pipeline.version_code('v65536', bad)

    def test_install_downgrade_names_the_offset(self):   # a refused downgrade says what is installed and how to lift the rebuild above it
        from porter.adb import Adb, AdbError
        a = Adb('adb')
        def run(*x, **_):
            if x[0] == 'install': raise AdbError('adb install: Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 589825 is older than current 589826]')
            return '    versionCode=589826 minSdk=29 targetSdk=34\n'
        a.run = run
        with self.assertRaises(AdbError) as e: a.install('x.apk', 'org.nx2a.test')
        self.assertIn('(589826)', str(e.exception)); self.assertIn('--vercode-offset', str(e.exception))

    def test_derived_alias_headroom(self):   # issue #26: a derived profile of a game with virtual address memory gets 8 GB more alias region, the heap unchanged
        d = profile_dir([('status', 'playable')])
        try:
            t = d / 'game.tsv'; base = t.read_text(encoding='utf-8')
            t.write_text(base + '# memory\nmem_reserve\t0x140000000\nmem_alias\t0x118000000\nmem_stack\t0x10000000\nsys_resource\t0x1000000\n', encoding='utf-8')
            self.assertEqual(pipeline.alias_headroom(t), (0x118000000, 0x318000000))
            g = game.load(d.name)
            self.assertEqual((g.int('mem_reserve'), g.int('mem_alias'), g.int('mem_stack')), (0x340000000, 0x318000000, 0x10000000))
            self.assertIn('# porter: 8 GB more alias region', t.read_text(encoding='utf-8'))
            vam = base + 'mem_reserve\t0x140000000\nmem_alias\t0x118000000\nmem_stack\t0x10000000\nsys_resource\t0x1000000\n'
            t.write_text(vam, encoding='utf-8'); self.assertEqual(pipeline.alias_headroom(t, 16), (0x118000000, 0x518000000))   # --alias-headroom 16
            self.assertIn('# porter: 16 GB more alias region', t.read_text(encoding='utf-8'))
            t.write_text(vam, encoding='utf-8'); self.assertIsNone(pipeline.alias_headroom(t, 0)); self.assertEqual(t.read_text(encoding='utf-8'), vam)   # 0: as it is
            for rows in ('sys_resource\t0x0\n', ''):   # no virtual address memory, or a profile older than the key (heap games): as it is
                t.write_text(base + 'mem_reserve\t0x100000000\nmem_alias\t0x10000000\nmem_stack\t0x10000000\n' + rows, encoding='utf-8')
                self.assertIsNone(pipeline.alias_headroom(t)); self.assertIsNone(pipeline.alias_headroom(t, 16))
                self.assertEqual(game.load(d.name).int('mem_alias'), 0x10000000)
        finally: shutil.rmtree(d.parent, ignore_errors=True); os.environ.pop('DM_GAMES_DIR', None)

    def test_mt_replay_option(self):   # --mt-replay: off unless given; the port's env rows then end with DM_VK_MT_REPLAY=1 (gfx_vk_dq.c)
        from porter import cli
        self.assertTrue(cli._parser().parse_args(['port', 'x', '--mt-replay']).mt_replay); self.assertFalse(cli._parser().parse_args(['port', 'x']).mt_replay)
        self.assertFalse(pipeline.Options(Path(".")).mt_replay)

    def test_app_option(self):   # the nx2android app's mode is the default (no APK, no install; the pack carries the host); --game-apk turns it off
        from porter import cli
        self.assertTrue(cli._parser().parse_args(['port', 'x']).app); self.assertTrue(cli._parser().parse_args(['port', 'x', '--app']).app)
        self.assertFalse(cli._parser().parse_args(['port', 'x', '--game-apk']).app)
        self.assertFalse(pipeline.Options(Path(".")).app)   # the pipeline's own default is untouched: the CLI decides the mode
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit): cli._parser().parse_args(['port', 'x', '--app', '--game-apk'])

    def test_shared_font_option(self):   # --shared-font FILE: parsed, and a file that is not a .ttf fails before any stage
        from porter import cli
        self.assertEqual(cli._parser().parse_args(['port', 'x', '--shared-font', 'cjk.ttf']).shared_font, 'cjk.ttf')
        self.assertIsNone(cli._parser().parse_args(['port', 'x']).shared_font)
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_game_'))
        try:
            (t / 'cjk.ttc').write_bytes(b'x')
            for bad in ('cjk.ttc', 'missing.ttf'):
                with self.assertRaisesRegex(pipeline.BuildError, 'shared-font'): pipeline.Port(pipeline.Options(t, shared_font=t / bad)).run()
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_vulkan_driver_option(self):   # --vulkan-driver FILE: parsed, and a file that is not a .so library fails before any stage
        from porter import cli
        self.assertEqual(cli._parser().parse_args(['port', 'x', '--vulkan-driver', 'libvulkan_freedreno.so']).vulkan_driver, 'libvulkan_freedreno.so')
        self.assertIsNone(cli._parser().parse_args(['port', 'x']).vulkan_driver)
        # the APK carries it as lib*.so, the only names the package manager extracts; env.txt's DM_VK_DRIVER names that file
        self.assertEqual(pipeline.driver_lib_name(Path('/d/libvulkan_freedreno.so')), 'libvulkan_freedreno.so')
        self.assertEqual(pipeline.driver_lib_name(Path('/d/vulkan.purple.so')), 'libvulkan.purple.so')
        self.assertEqual(pipeline.driver_lib_name('vulkan.freedreno.so'), 'libvulkan.freedreno.so')
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_game_'))
        try:
            with self.assertRaises(pipeline.BuildError): pipeline.Port(pipeline.Options(t, vulkan_driver=t / 'driver.txt')).run()
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_alias_headroom_option(self):   # --alias-headroom: parsed, checked before any stage, and kept as None (automatic) when not given
        from porter import cli
        self.assertEqual(cli._parser().parse_args(['port', 'x', '--alias-headroom', '16']).alias_headroom, 16)
        self.assertIsNone(cli._parser().parse_args(['port', 'x']).alias_headroom)
        for ok in (None, 0, pipeline.ALIAS_HEADROOM_MAX): self.assertEqual(pipeline.alias_headroom_gb(ok), ok)
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_game_'))
        try:
            for bad in (-1, pipeline.ALIAS_HEADROOM_MAX + 1):
                with self.assertRaises(pipeline.BuildError) as e: pipeline.Port(pipeline.Options(folder=t, alias_headroom=bad)).run()
                self.assertIn('--alias-headroom', str(e.exception))
        finally: shutil.rmtree(t, ignore_errors=True)

    def test_mem_profile(self):   # --alias-headroom on a version the profile was brought up on: a copy in profile-mem/ in front, without patches.py; none without the option
        d = profile_dir([('status', 'playable')]); environ = dict(os.environ); t = Path(tempfile.mkdtemp(prefix='nx2a_test_build_'))
        try:
            (d / 'game.tsv').write_text((d / 'game.tsv').read_text(encoding='utf-8') + 'mem_reserve\t0x140000000\nmem_alias\t0x118000000\nmem_stack\t0x10000000\nsys_resource\t0x1000000\n',
                                        encoding='utf-8')
            (d / 'patches.py').write_text('CUTS = {}\n', encoding='utf-8')
            port = pipeline.Port(pipeline.Options(folder=t, alias_headroom=16)); port.env = {}; port.work = t; port.g = game.load(d.name)
            port.mem_profile()
            self.assertEqual(port.g.dir, (t / 'profile-mem' / d.name).resolve())
            self.assertEqual((port.g.int('mem_alias'), port.g.int('mem_reserve')), (0x518000000, 0x540000000))
            self.assertFalse((port.g.dir / 'patches.py').exists()); self.assertEqual(os.environ.get('DM_GAMES_FIRST'), '1')
            shutil.rmtree(t / 'profile-mem')   # as extract() does before it
            port.o.alias_headroom = None; port.g = game.load(d.name); port.mem_profile()
            self.assertFalse((t / 'profile-mem').exists()); self.assertEqual(port.g.dir, d.resolve())
        finally:
            os.environ.clear(); os.environ.update(environ); shutil.rmtree(d.parent, ignore_errors=True); shutil.rmtree(t, ignore_errors=True)

    def test_lost_patches(self):   # issue #26: a derived module of another build than the tested one, which has cuts, is named; one with cuts of its own, or no cuts lost, is not
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_exefs_'))
        try:
            for m, bid in (('main', 'aa' * 16), ('sdk', 'bb' * 16), ('subsdk0', 'cc' * 16)): (d / m).write_bytes(b'NSO0' + bytes(0x3c) + bytes.fromhex(bid))
            builds = {'v65536': {'display': '1.1.0', 'main': '11' * 16, 'sdk': '22' * 16, 'subsdk0': 'cc' * 16}}
            table = {'22' * 16: ('dm/tools/sdk_patches.py', ['AddressRegionManager constructor: node area limit', 'AddressRegionManager constructor: length']), '33' * 16: ('x', ['y'])}
            lost = pipeline.lost_patches(builds, d, ['main', 'sdk', 'subsdk0'], table)
            self.assertEqual(len(lost), 1); self.assertTrue(lost[0].startswith('sdk build bbbbbbbb… is not the tested 22222222…'))
            self.assertIn('2 registered patches (dm/tools/sdk_patches.py: AddressRegionManager constructor)', lost[0])
            table['bb' * 16] = ('dm/tools/sdk_patches.py', ['z'])   # the derived build has cuts of its own
            self.assertEqual(pipeline.lost_patches(builds, d, ['main', 'sdk', 'subsdk0'], table), [])
            self.assertIn('6075151627016eef437c1312f2e3ed56', pipeline.registered_patches())   # the real files load
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_app_stem(self):
        self.assertEqual(pipeline.app_stem(game.load(NEO), 'NEO'), 'NeoTheWorldEndsWithYou')

    def test_disk_space(self):   # twice the game files, less what the build folder holds, plus 1 GB
        from types import SimpleNamespace as NS
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_game_')); (d / 'g.nsp').write_bytes(b'\0' * 2**20); work = d / 'build'
        p = NS(o=NS(folder=d, app=False), work=work, fam={'base': d / 'g.nsp', 'updates': [], 'dlc': []}); real = shutil.disk_usage
        try:
            shutil.disk_usage = lambda _: NS(free=2**30 + 2**20)
            with self.assertRaises(pipeline.BuildError): pipeline.Port.check_space(p)
            work.mkdir(); (work / 'x').write_bytes(b'\0' * 2**20); pipeline.Port.check_space(p)   # a build folder already there counts
        finally: shutil.disk_usage = real

    def space_port(self, folder, fam, work, app=False, pack_dir=None):   # a port-like object for the space check alone (the check reads the picked game's files and the build folder)
        from types import SimpleNamespace as NS
        return NS(o=NS(folder=folder, app=app), work=work, fam=fam,
                  **({'pack_dir_root': (lambda: pack_dir)} if pack_dir is not None else {}))

    def space_fits(self, port, free):   # does the check pass with this much free space on the disk?
        from types import SimpleNamespace as NS
        real = shutil.disk_usage
        try:
            shutil.disk_usage = lambda _: NS(free=free)
            pipeline.Port.check_space(port)
        except pipeline.BuildError as e: return str(e)
        finally: shutil.disk_usage = real
        return None

    def test_the_space_check_asks_each_volume(self):   # the pack may go to the removable card: the two volumes carry their own share, and the one that is short is named
        from types import SimpleNamespace as NS
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_vol_'))
        try:
            (d / 'g.nsp').write_bytes(b'\0' * 2**20)
            work = d / 'build'
            card = d / 'card/ports'
            p = self.space_port(d, {'base': d / 'g.nsp', 'updates': [], 'dlc': []}, work, pack_dir=card)
            real = shutil.disk_usage
            try:
                # the card is on another volume: the build's volume asks for the game's files, the card for the pack
                vols = lambda path: 2 if 'card' in str(path) else 1
                shutil.disk_usage = lambda path: NS(free=1024 if 'card' in str(path) else 2**30 + 2**20)
                with mock.patch.object(pipeline, 'volume_of', side_effect=vols):
                    with self.assertRaises(pipeline.BuildError) as e:
                        pipeline.Port.check_space(p)
                    self.assertRegex(str(e.exception), r'not enough disk space for the data pack in .*card')
                    # both volumes have room now
                    shutil.disk_usage = lambda path: NS(free=2**30 + 2**20)
                    pipeline.Port.check_space(p)
            finally: shutil.disk_usage = real
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_space_counts_the_picked_game_only(self):   # a folder of many games: the others are not this port's business (they once made a 1 GB game ask for 285 GB)
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_space_'))
        try:
            (d / 'a.nsp').write_bytes(b'\0' * 2**20)             # the picked game: 1 MB
            (d / 'b.nsp').write_bytes(b'\0' * 100 * 2**20)        # another game in the same folder: 100 MB
            work = d / 'build'
            p = self.space_port(d, {'base': d / 'a.nsp', 'updates': [], 'dlc': []}, work)
            self.assertIsNone(self.space_fits(p, 2**30 + 2 * 2**20))          # twice the picked game's files plus 1 GB fits
            err = self.space_fits(p, 2**30 + 2 * 2**20 - 1)
            self.assertIsNotNone(err)                                    # a byte less does not
            self.assertRegex(err, r'not enough disk space on .+: the port needs about [\d.]+ GB more, [\d.]+ GB is free')
            self.assertFalse(work.exists())                              # refused before anything was written
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_space_counts_the_game_s_updates_and_dlc(self):   # the picked game's own updates and DLC count (the port reads them)
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_space_'))
        try:
            (d / 'a.nsp').write_bytes(b'\0' * 2**20); (d / 'u.nsp').write_bytes(b'\0' * 2**20); (d / 'c.nsp').write_bytes(b'\0' * 2**20)
            work = d / 'build'
            fam = {'base': d / 'a.nsp', 'updates': [('v1', d / 'u.nsp')], 'dlc': [('0100000000011000', 'v0', d / 'c.nsp')]}
            p = self.space_port(d, fam, work)
            self.assertIsNone(self.space_fits(p, 2**30 + 6 * 2**20))      # three files of 1 MB, twice over, plus 1 GB
            self.assertIsNotNone(self.space_fits(p, 2**30 + 6 * 2**20 - 1))
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_space_in_the_app_s_mode(self):   # the app's mode asks for the game's files once over: the pack points at the build folder's own RomFS instead of copying it
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_space_'))
        try:
            (d / 'a.nsp').write_bytes(b'\0' * 2**20)
            work = d / 'build'
            p = self.space_port(d, {'base': d / 'a.nsp', 'updates': [], 'dlc': []}, work, app=True)
            self.assertIsNone(self.space_fits(p, 2**30 + 2**20))          # the game's files once over, plus 1 GB
            self.assertIsNotNone(self.space_fits(p, 2**30 + 2**20 - 1))
        finally: shutil.rmtree(d, ignore_errors=True)

    def test_space_less_the_build_folder(self):   # what an earlier build left in the build folder is not needed again
        d = Path(tempfile.mkdtemp(prefix='nx2a_test_space_'))
        try:
            (d / 'a.nsp').write_bytes(b'\0' * 2**20)
            work = d / 'build'; work.mkdir(); (work / 'x').write_bytes(b'\0' * 2**20)
            p = self.space_port(d, {'base': d / 'a.nsp', 'updates': [], 'dlc': []}, work)
            self.assertIsNone(self.space_fits(p, 2**30 + 2**20))          # 2 MB wanted, 1 MB of it already there
            self.assertIsNotNone(self.space_fits(p, 2**30 + 2**20 - 1))
        finally: shutil.rmtree(d, ignore_errors=True)


def _hfs0(files):   # an HFS0 of (name, data): the XCI's partitions (hash fields left zero: nothing here reads them)
    strs = b''.join(n.encode() + b'\0' for n, _ in files); ent, off = b'', 0
    for i, (n, d) in enumerate(files):
        ent += struct.pack('<QQII8x32s', off, len(d), sum(len(m.encode()) + 1 for m, _ in files[:i]), 0, b''); off += len(d)
    return struct.pack('<4sII4x', b'HFS0', len(files), len(strs)) + ent + strs + b''.join(d for _, d in files)


@unittest.skipUnless(importlib.util.find_spec('Crypto'), 'no pycryptodome')
class Dumps(unittest.TestCase):   # porter/dumps.py: NSZ / XCZ → NSP; an NCZ made here as nsz makes it, from an NCA of a plain section and an AES-CTR one
    def setUp(self):
        from Crypto.Cipher import AES
        from porter import dumps; self.dumps = dumps
        rnd = random.Random(7); b = lambda n: bytes(rnd.getrandbits(8) for _ in range(n))
        self.key, self.ctr = b(16), b(8) + b'\0' * 8
        head, plain = b(0x4000), b(0x2000) + bytes(0x18000)   # the CTR part compresses (zeros), so zstd has something to do
        self.nca = head + plain[:0x2000] + AES.new(self.key, AES.MODE_CTR, nonce=self.ctr[:8], initial_value=0x6000 >> 4).encrypt(plain[0x2000:])
        self.name = hashlib.sha256(self.nca).hexdigest()[:32]
        secs = struct.pack('<8sQ', b'NCZSECTN', 2) + struct.pack('<QQQQ16s16s', 0x4000, 0x2000, 1, 0, bytes(16), bytes(16)) + struct.pack('<QQQQ16s16s', 0x6000, 0x18000, 3, 0, self.key, self.ctr)
        z = dumps.zstd_mod()
        self.solid = head + secs + z.compress(plain)
        bs = 1 << 14; blocks = [plain[i:i + bs] for i in range(0, len(plain), bs)]
        comp = [z.compress(k) if i else k for i, k in enumerate(blocks)]   # the first block stored as it is (random data: compressing does not shrink it)
        self.block = head + secs + struct.pack('<8sBBBBIQ', b'NCZBLOCK', 2, 1, 0, 14, len(blocks), len(plain)) + struct.pack(f'<{len(blocks)}I', *map(len, comp)) + b''.join(comp)
        self.d = Path(tempfile.mkdtemp(prefix='nx2a_test_dumps_'))

    def nsz(self, ncz, name='g.nsz'):
        files = [(self.name + '.ncz', ncz), ('0100000000aaa0000000000000000000.tik', b't' * 0x2c0)]
        hdr = self.dumps._pfs0_header([n for n, _ in files], [len(d) for _, d in files])
        (self.d / name).write_bytes(hdr + b''.join(d for _, d in files)); return self.d / name

    def check(self, src):
        self.assertEqual(self.dumps.size(src), len(self.dumps._pfs0_header([self.name + '.nca', 'x' * 36], [0, 0])) + len(self.nca) + 0x2c0)
        out = self.dumps.decompress(src, self.d / self.dumps.unpacked_name(src))
        self.assertEqual(out.stat().st_size, self.dumps.size(src)); self.assertEqual(out.stat().st_mtime_ns, src.stat().st_mtime_ns)
        with open(out, 'rb') as f: got = {n: (f.seek(o), f.read(s))[1] for n, o, s in self.dumps.entries(out)}
        self.assertEqual(got[self.name + '.nca'], self.nca); self.assertEqual(len(got), 2)

    def test_solid_and_block(self):
        self.check(self.nsz(self.solid)); self.check(self.nsz(self.block, 'b.nsz'))

    def test_first_section_in_the_header(self):   # nsz 5.0 starts the first section at 0xc00, inside the header it keeps as it is (a DLC NSZ it made, 2026-10-29)
        s = bytearray(self.solid); s[0x4000 + 16:0x4000 + 32] = struct.pack('<QQ', 0xc00, 0x2000 + 0x4000 - 0xc00)
        self.check(self.nsz(bytes(s)))

    def test_xcz_is_its_secure_partition(self):
        secure = _hfs0([(self.name + '.ncz', self.block), ('0100000000aaa0000000000000000000.tik', b't' * 0x2c0)])
        root = _hfs0([('update', _hfs0([])), ('secure', secure)])
        x = bytearray(0x200); x[0x100:0x104] = b'HEAD'; x[0x130:0x138] = struct.pack('<Q', 0x200)
        (self.d / 'g.xcz').write_bytes(bytes(x) + root); self.check(self.d / 'g.xcz')

    def test_damage_is_found(self):   # a changed byte: a zstd error, or an NCA whose SHA-256 is not its name; either way a BuildError and no NSP
        for ncz, at in ((self.solid, 0x4000 + 16 + 0x80 + 40), (self.block, -1), (self.block, 0x4000 + 16 + 0x80 + 24 + 100)):
            bad = bytearray(ncz); bad[at] ^= 1
            with self.assertRaises(pipeline.BuildError): self.dumps.decompress(self.nsz(bytes(bad)), self.d / 'out.nsp')
            self.assertFalse((self.d / 'out.nsp').exists())

    def test_scan_takes_compressed_dumps(self):   # upstream's scan_nsps with porter's kinds: the NSZ's ticket names its title ID
        self.nsz(self.solid, 'Game [v0].nsz')
        app, fam = pipeline.nsp.scan_nsps(self.d, self.dumps.EXTS)
        self.assertEqual(app, 0x0100000000AAA000); self.assertEqual(fam['base'].suffix, '.nsz')

    def test_space_counts_a_compressed_dump_decompressed(self):   # an NSZ counts as its decompressed size, and once more for the NSP it becomes while it is extracted
        from types import SimpleNamespace as NS
        src = self.nsz(self.solid); size = self.dumps.size(src)
        work = self.d / 'build'
        p = NS(o=NS(folder=self.d, app=False), work=work, fam={'base': src, 'updates': [], 'dlc': []})
        real = shutil.disk_usage
        try:
            shutil.disk_usage = lambda _: NS(free=2**30 + 3 * size)
            pipeline.Port.check_space(p)                       # twice the decompressed size plus its NSP, plus 1 GB
            shutil.disk_usage = lambda _: NS(free=2**30 + 3 * size - 1)
            with self.assertRaises(pipeline.BuildError): pipeline.Port.check_space(p)
        finally: shutil.disk_usage = real


@unittest.skipUnless(importlib.util.find_spec('Crypto'), 'no pycryptodome')
class UntaggedXci(unittest.TestCase):   # issue #28: a cartridge dump named by its release ("hr-bdlwa.xci") has no ticket and no [title ID]: the content meta NCA's header names it
    BASE = 0x01009D901BC56000

    def setUp(self):
        rnd = random.Random(28); self.hk = bytes(rnd.getrandbits(8) for _ in range(32))
        self.d = Path(tempfile.mkdtemp(prefix='nx2a_test_xci_'))
        self.keys = self.d / 'prod.keys'; self.keys.write_text(f'header_key = {self.hk.hex()}\n')

    def nca(self, tid, ctype):   # an NCA header (0xc00 bytes) whose second sector is encrypted as Nintendo does: AES-XTS, the sector number big-endian as the tweak
        from Crypto.Cipher import AES
        p = bytearray(0x200); p[:4] = b'NCA3'; p[5] = ctype; struct.pack_into('<Q', p, 0x10, tid)
        data, t = AES.new(self.hk[:16], AES.MODE_ECB), int.from_bytes(AES.new(self.hk[16:], AES.MODE_ECB).encrypt((1).to_bytes(16, 'big')), 'little')
        c = b''
        for i in range(0, 0x200, 16):
            tb = t.to_bytes(16, 'little'); c += bytes(a ^ b for a, b in zip(data.encrypt(bytes(a ^ b for a, b in zip(p[i:i + 16], tb))), tb))
            t = (t << 1 & (1 << 128) - 1) ^ (0x87 if t >> 127 else 0)
        return bytes(0x200) + c + bytes(0x800)

    def xci(self, name, ncas, key_area=False):
        root = _hfs0([('update', _hfs0([])), ('secure', _hfs0(ncas))])
        x = bytearray(0x200); x[0x100:0x104] = b'HEAD'; x[0x130:0x138] = struct.pack('<Q', 0x200)
        (self.d / name).write_bytes(bytes(0x1000 if key_area else 0) + bytes(x) + root)

    def test_scan_reads_the_meta_nca(self):   # an update's program NCA carries the base game's title ID: only the meta NCA's counts, and the smallest of them is the base game
        self.xci('hr-bdlwa.xci', [('a.nca', self.nca(self.BASE + 0x800, 0)), ('b.cnmt.nca', self.nca(self.BASE + 0x800, 1)), ('c.nca', self.nca(self.BASE, 0)), ('d.cnmt.nca', self.nca(self.BASE, 1))])
        app, fam = pipeline.nsp.scan_nsps(self.d, ('nsp', 'xci'), self.keys)
        self.assertEqual(app, self.BASE); self.assertEqual(fam['base'].name, 'hr-bdlwa.xci')
        self.xci('hr-bdlwa.xci', [('c.nca', self.nca(self.BASE + 0x800, 0)), ('d.cnmt.nca', self.nca(self.BASE + 0x800, 1))], key_area=True)
        self.assertEqual(pipeline.nsp.title_of(self.d / 'hr-bdlwa.xci', self.hk), self.BASE + 0x800)

    def test_update_ticket_on_the_card(self):   # Crayon Shin-chan's cartridge: its update's ticket beside the base game's ticketless NCAs is not the card's title
        tik = f'{self.BASE + 0x800:016x}0000000000000011.tik'
        self.xci('Game (Base).xci', [(tik, b't' * 0x2c0), ('b.cnmt.nca', self.nca(self.BASE + 0x800, 1)), ('d.cnmt.nca', self.nca(self.BASE, 1))])
        self.assertEqual(pipeline.nsp.title_of(self.d / 'Game (Base).xci', self.hk), self.BASE)
        self.assertEqual(pipeline.nsp.title_of(self.d / 'Game (Base).xci'), self.BASE + 0x800)   # without prod.keys only the ticket is there to read

    def test_without_keys_the_error_says_how(self):
        self.xci('hr-bdlwa.xci', [('d.cnmt.nca', self.nca(self.BASE, 1))])
        with self.assertRaisesRegex(pipeline.BuildError, r'hr-bdlwa\.xci: no ticket .*no prod\.keys.*\[01009D901BC56000\]\[v0\]'): pipeline.nsp.scan_nsps(self.d, ('nsp', 'xci'))
        self.keys.write_text(f'header_key = {bytes(32).hex()}\n')   # another key: the header does not decrypt to NCA3
        with self.assertRaisesRegex(pipeline.BuildError, 'no content meta NCA'): pipeline.nsp.scan_nsps(self.d, ('nsp', 'xci'), self.keys)


class FpsLocker(unittest.TestCase):   # porter/fpslocker.py: FPSLocker-Warehouse configs → the host's rows (hle_fpslock.c DM_FPS_LOCKS) and the rewriter's cuts
    BID = 'd49165c05ead8ae140f189aa97934f39'
    UNITY = ('ALL_FPS:\n  # Vsync\n  -\n    type: evaluate_write\n    address: [MAIN, 0x7A642D8]\n    value_type: int32\n    value: VSYNC_TARGET\n'
             '  -\n    type: evaluate_write\n    address: [MAIN, 0x7A642E4]\n    value_type: float\n    value: "1 / FPS_LOCK_TARGET"\n')   # the shape of Viractal 1.4.5's config

    def rows(self, text, image=b''):
        from porter import fpslocker
        tsv, cuts = fpslocker.convert(text, image, self.BID, 'test')
        return [l.split('\t') for l in tsv.splitlines() if not l.startswith('#')], cuts

    def test_unity_vsync_and_frame_time(self):   # 2 vsyncs / 1/30 s at 30; 1 vsync / 1/62 s at 60 (FPS_LOCK_TARGET is the target + 2 at the refresh rate)
        rows, cuts = self.rows(self.UNITY)
        self.assertEqual(rows[0], ['build', self.BID]); self.assertEqual(cuts, [])
        self.assertEqual(rows[1][1:], ['int32', '0x7a642d8', '2', '1', '-'])
        self.assertEqual(rows[2][1:3], ['float', '0x7a642e4']); self.assertAlmostEqual(float(rows[2][3]), 1 / 30); self.assertAlmostEqual(float(rows[2][4]), 1 / 62)

    def test_expressions(self):
        from porter.fpslocker import evaluate
        self.assertEqual(evaluate('TruncDec(FRAMETIME_TARGET, 2)', 30), 33.33); self.assertEqual(evaluate('2^3', 60), 8)
        self.assertEqual(evaluate('VSYNC_TARGET', 60), 1); self.assertEqual(evaluate('floor(FPS_TARGET / 7)', 60), 8)
        with self.assertRaises(Exception): evaluate('__import__("os")', 60)

    def test_pointers_lists_and_compares(self):
        rows, _ = self.rows('ALL_FPS:\n  -\n    type: compare\n    compare_address: [MAIN, 0x10, 0x8]\n    compare_type: "!="\n    compare_value_type: int8\n    compare_value: 0\n'
                            '    address: [MAIN, 0x20, 0x30, -4]\n    value_type: int32\n    value: [2, 2]\n  -\n    type: block\n    what: timing\n')
        self.assertEqual(rows[1][1:], ['int32', '0x20,0x30,-0x4', '2,2', '2,2', 'int8 0x10,0x8 != 0'])
        self.assertEqual(len(rows), 2)   # block timing is FPSLocker's own frame delay: dropped

    def test_refused_whole(self):   # entries work together: one the host cannot apply refuses the config
        from porter import fpslocker
        for text in (self.UNITY + 'DECLARATIONS:\n  -\n    type: variable\n    name: x\n    value_type: float\n    default_value: 0\n',
                     self.UNITY + 'MASTER_WRITE:\n  -\n    type: asm_a64\n    main_offset: 0x10\n    instructions: [[b.ne, :goto1], [nop], :goto1, [nop]]\n',   # labels parse
                     'ALL_FPS:\n  -\n    type: write\n    address: [HEAP, 0x10]\n    value_type: int32\n    value: 1\n',
                     'ALL_FPS:\n  -\n    type: compare\n    compare_address: [MAIN, 0x10]\n    compare_type: "=="\n    compare_value_type: int8\n    compare_value: 1\n    value_type: refresh_rate\n    value: 30\n',
                     'ALL_FPS: [\n'):
            with self.assertRaises(fpslocker.Refused): fpslocker.convert(text, b'', self.BID)

    def test_master_write_bytes_are_cuts(self):   # the original bytes come from main's image, so the rewriter can assert them
        image = bytes(range(256)) * 4
        _, cuts = self.rows(self.UNITY + 'MASTER_WRITE:\n  -\n    type: bytes\n    main_offset: 0x10\n    value_type: uint8\n    value: [0x1F, 0x20, 0x03]\n', image)
        self.assertEqual(cuts, [(0x10, '101112', '1f2003', 'FPSLocker-Warehouse MASTER_WRITE entry 1')])
        from porter import fpslocker
        ns = {}; exec(fpslocker.patches_py(self.BID, cuts), ns); self.assertEqual(ns['CUTS'][self.BID][0][:3], (0x10, '101112', '1f2003'))

    def test_url(self):
        from porter import fpslocker
        self.assertTrue(fpslocker.url('0100fd501f95a000', self.BID).endswith('/patches/0100FD501F95A000/D49165C05EAD8AE1.yaml'))


class Saves(unittest.TestCase):   # porter/saves.py: the save folders on the device, a .zip or folder to import (no device: the shell answers are made up)
    class FakeAdb:
        def __init__(self, sizes, times, packages=('org.nx2a.a',)): self.sizes, self.times, self.packages, self.cmds = sizes, times, packages, []
        def remote_sizes(self, rdir): return dict(self.sizes)
        def shell(self, script, **kw):
            self.cmds.append(script)
            if script.startswith('pm list packages'): return ''.join(f'package:{p}\n' for p in self.packages)
            if 'stat -c "%Y %n"' in script: return ''.join(f'{t} {n}\n' for n, t in self.times.items())
            return ''

    def test_listing(self):   # newest first; a device save counts with its own; hidden folders (an export still copying) and the request files left out
        from porter import saves
        adb = self.FakeAdb({'20261030-100000/a.dat': 10, '20261030-100000.device/d.dat': 5, '20261029-090000/a.dat': 7, 'result.txt': 40, '.x.part/a.dat': 9, 'console/sub/b.bin': 3},
                           {'20261030-100000': 200, '20261030-100000.device': 200, '20261029-090000': 100, 'console': 300})
        rows = saves.listing(adb, 'org.nx2a.a')
        self.assertEqual([r['name'] for r in rows], ['console', '20261030-100000', '20261029-090000'])
        self.assertEqual((rows[1]['files'], rows[1]['bytes'], rows[1]['device']), (2, 15, True))
        self.assertEqual((rows[0]['files'], rows[0]['bytes'], rows[0]['device']), (1, 3, False))

    def test_pick_app(self):
        from porter import saves
        from porter.upstream import BuildError
        self.assertEqual(saves.pick_app(self.FakeAdb({}, {}), ''), 'org.nx2a.a')
        two = self.FakeAdb({}, {}, ('org.nx2a.a', 'org.nx2a.b'))
        self.assertEqual(saves.pick_app(two, 'org.nx2a.b'), 'org.nx2a.b')
        with self.assertRaises(BuildError): saves.pick_app(two, '')
        with self.assertRaises(BuildError): saves.pick_app(two, 'org.nx2a.c')

    def test_zip(self):   # a .zip of one folder imports that folder; one of loose files imports them; a path out of the archive is refused
        import zipfile
        from porter import saves
        from porter.upstream import BuildError
        t = Path(tempfile.mkdtemp(prefix='nx2a_test_saves_'))
        with zipfile.ZipFile(t / 'one.zip', 'w') as z: z.writestr('MySave/main.dat', b'x'); z.writestr('MySave/sub/b.dat', b'y')
        with zipfile.ZipFile(t / 'loose.zip', 'w') as z: z.writestr('main.dat', b'x'); z.writestr('other.dat', b'y')
        with zipfile.ZipFile(t / 'bad.zip', 'w') as z: z.writestr('../evil.dat', b'x')
        u = Path(tempfile.mkdtemp(dir=t)); self.assertEqual(saves._unpacked(t / 'one.zip', u), u / 'MySave')
        u = Path(tempfile.mkdtemp(dir=t)); self.assertEqual(sorted(p.name for p in saves._unpacked(t / 'loose.zip', u).iterdir()), ['main.dat', 'other.dat'])
        with self.assertRaises(BuildError): saves._unpacked(t / 'bad.zip', Path(tempfile.mkdtemp(dir=t)))
        self.assertEqual(saves._unpacked(t, None), t)   # a folder: as it is


class UnrealShaders(unittest.TestCase):
    """porter/pipeline.py device_ue: the Unreal shader code archives decoded with the game's own Oodle decoder, in rounds"""

    class FakePort:
        """the parts of Port.device_ue it uses"""
        UE_ROUNDS = 4
        def __init__(self, root, answers):
            self.versions, self.dirs = ['v1'], {'v1': root / 'extract' / 'v1'}
            self.work, self.logs = root / 'work', root / 'logs'
            self.oodle_where = 'here'
            self.answers, self.asked = answers, []   # answers: the raw bytes each stream decodes to (missing: the decoder fails)
            for d in (self.dirs['v1'] / 'RomFS', self.dirs['v1'] / 'ExeFS', self.work / 'tmp', self.logs): d.mkdir(parents=True, exist_ok=True)
        def oodle_job(self, job, out, bnsh=False):
            """the game's own Oodle decoder: the job's streams, decoded (a stream it fails on comes back with no bytes)"""
            b = job.read_bytes(); n = struct.unpack_from('<I', b, 4)[0]; o = 8; recs = []
            for _ in range(n):
                ln, raw = struct.unpack_from('<II', b, o); o += 8
                recs.append((b[o:o + ln], raw)); o += ln
            self.asked.append([c for c, _ in recs])
            ans = b'DMOR' + struct.pack('<I', n)
            for comp, raw in recs:
                got = self.answers.get(comp, b'')
                ans += struct.pack('<I', len(got)) + got
            out.write_bytes(ans)
        def device_oodle_job(self, job, out, bnsh=False):   # the same decoder, run on the device (upstream's pipeline.py calls it by this name)
            return self.oodle_job(job, out, bnsh)

    def uepak_job(self, rounds):
        """what uepak.job does: it asks its decoder for the streams of this round (rounds: a list of stream lists, one per round) and it lists the archives"""
        state = {'n': 0}
        def job(romfs, main, jd, decode=None):
            (Path(jd) / 'archives').mkdir(parents=True, exist_ok=True)   # the real job writes the archives here
            i = state['n']; state['n'] += 1
            for comp, raw in rounds[min(i, len(rounds) - 1)]: decode(comp, raw)
            return [{'name': 'ShaderArchive-Global', 'shaders': 2, 'compressed': 1, 'compression': 'Oodle'}], 0
        return job

    def uepak_finish(self, jd, out):
        """what uepak.finish does: the decoded archives land in out"""
        Path(out).mkdir(parents=True, exist_ok=True)
        (Path(out) / 'ShaderArchive-Global.ushaderbytecode').write_bytes(b'shaders')

    def port(self, rounds, answers, stamp_size=None, root=None):
        """a port with the fake decoder and the fake archives"""
        root = root or Path(tempfile.mkdtemp(prefix='nx2a_test_ue_'))
        p = self.FakePort(root, answers)
        p.root = root
        (p.dirs['v1'] / 'RomFS' / 'Content').mkdir(parents=True, exist_ok=True)
        (p.dirs['v1'] / 'RomFS' / 'Content' / 'pakchunk0-Switch.pak').write_bytes(b'pak')
        p.stamp = pipeline.nsp.ue_stamp(p.dirs['v1'])   # what the version's archives were made from: its paks (the path is the platform's own)
        if stamp_size is not None:
            (p.dirs['v1'] / 'ue_shaders').mkdir(parents=True, exist_ok=True)
            (p.dirs['v1'] / 'ue_shaders' / 'stamp').write_text(p.stamp.rsplit('\t', 1)[0] + f'\t{stamp_size}\n', encoding='utf-8')
        patches = [mock.patch.object(pipeline.uepak, 'job', self.uepak_job(rounds)),
                   mock.patch.object(pipeline.uepak, 'finish', self.uepak_finish)]
        for q in patches: q.start()
        self.addCleanup(lambda: [q.stop() for q in patches])
        return p

    def test_the_rounds_advance_until_nothing_is_left(self):   # the same stream again is a cache hit: the round asks for nothing and the decode is done
        p = self.port([[(b'one', 6)]], {b'one': b'123456'})
        pipeline.Port.device_ue(p)
        self.assertEqual(p.asked, [[b'one']])   # the decoder was asked once: the second round wanted nothing
        self.assertEqual((p.dirs['v1'] / 'ue_shaders' / 'ShaderArchive-Global.ushaderbytecode').read_bytes(), b'shaders')
        self.assertEqual((p.dirs['v1'] / 'ue_shaders' / 'stamp').read_text(), p.stamp)

    def test_a_stream_the_decoder_fails_on_is_asked_again(self):   # the round that decodes none of them says so
        from porter.upstream import BuildError
        p = self.port([[(b'one', 6), (b'two', 4)]], {b'one': b'123456'})
        with self.assertRaises(BuildError) as e: pipeline.Port.device_ue(p)
        self.assertIn('decoded none of 1 streams', str(e.exception))
        self.assertEqual(p.asked, [[b'one', b'two'], [b'two']])   # the stream that failed is asked for again, the decoded one is not

    def test_the_rounds_end_with_an_error(self):   # a new stream every round: the rounds run out
        from porter.upstream import BuildError
        rounds = [[(f'stream {i}'.encode(), 4)] for i in range(8)]
        p = self.port(rounds, {f'stream {i}'.encode(): b'1234' for i in range(8)})
        with self.assertRaises(BuildError) as e: pipeline.Port.device_ue(p)
        self.assertIn('still need Oodle streams after 4 rounds', str(e.exception))
        self.assertEqual(len(p.asked), 4)

    def test_a_stamped_decode_is_not_done_again(self):   # the stamp of a version whose archives are decoded: it is left alone
        p = self.port([[(b'one', 6)]], {b'one': b'123456'}, stamp_size=3)   # the stamp of this version's paks
        pipeline.Port.device_ue(p)
        self.assertEqual(p.asked, [])
        self.assertFalse((p.dirs['v1'] / 'ue_shaders' / 'ShaderArchive-Global.ushaderbytecode').exists())
        p = self.port([[(b'one', 6)]], {b'one': b'123456'}, stamp_size=9)   # another pak: the stamp is not the one
        pipeline.Port.device_ue(p)
        self.assertEqual(p.asked, [[b'one']])

    def test_a_stopped_decode_continues_with_the_streams_it_has(self):   # the streams a round decoded are on disk: the next run asks for the rest only
        from porter.upstream import BuildError
        p = self.port([[(b'one', 6), (b'two', 4)]], {b'one': b'123456'})   # the decoder fails on the second stream
        with self.assertRaises(BuildError): pipeline.Port.device_ue(p)
        self.assertEqual(p.asked, [[b'one', b'two'], [b'two']])   # the stream that was decoded is not asked for again
        p = self.port([[(b'one', 6), (b'two', 4)]], {b'one': b'123456', b'two': b'5678'}, root=p.root)   # the run again, the decoder gets both
        pipeline.Port.device_ue(p)
        self.assertEqual(p.asked, [[b'two']])   # the stream on disk is not asked for again


class SubfolderScan(unittest.TestCase):   # the updates and the DLC in a subfolder next to the base game (a download's layout: 本体加补丁/1DLC/)
    BASE = 0x0100B8A026F14000

    def setUp(self):
        tmp = tempfile.mkdtemp(prefix='nx2a_test_subfolder_')
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        self.root = Path(tmp)

    def dump(self, name, tid):
        """an NSP whose ticket names the title ID"""
        files = [(f'{tid:016x}0000000000000000.tik', bytes(16))]
        names = b''.join(n.encode() + b'\0' for n, _ in files)
        table, offset, string = b'', 0, 0
        for n, data in files:
            table += struct.pack('<QQII32sQ', offset, len(data), string, 0, bytes(32), 0)
            offset += len(data)
            string += len(n) + 1
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(dumps._pfs0_header([n for n, _ in files], [len(d) for _, d in files]) + b''.join(d for _, d in files))
        return path

    def test_the_dlc_in_a_subfolder_is_taken(self):
        base = self.dump('Game [v0].nsp', self.BASE)
        dlc = self.dump('1DLC/DLC [v0].nsp', self.BASE + 0x1002)
        app, fam = pipeline.nsp.scan_nsps(self.root, ('nsp',), None, base)
        self.assertEqual(app, self.BASE)
        self.assertEqual(fam['base'], base)
        self.assertEqual(fam['dlc'], [(self.BASE + 0x1002, 0, dlc)])

    def test_the_update_in_a_subfolder_is_taken(self):
        base = self.dump('Game [v0].nsp', self.BASE)
        update = self.dump('patch/Update [v262144].nsp', self.BASE + 0x800)
        app, fam = pipeline.nsp.scan_nsps(self.root, ('nsp',), None, base)
        self.assertEqual(app, self.BASE)
        self.assertEqual(fam['updates'], [(262144, update)])

    def test_another_game_in_a_subfolder_is_left_out(self):
        base = self.dump('Game [v0].nsp', self.BASE)
        self.dump('other/Other [v0].nsp', 0x0100AAAA026F14000)
        app, fam = pipeline.nsp.scan_nsps(self.root, ('nsp',), None, base)
        self.assertEqual(app, self.BASE)
        self.assertEqual(fam['dlc'], [])
        self.assertEqual(fam['updates'], [])

    def test_the_files_beside_the_base_are_taken_as_before(self):
        base = self.dump('Game [v0].nsp', self.BASE)
        update = self.dump('Update [v262144].nsp', self.BASE + 0x800)
        dlc = self.dump('DLC [v0].nsp', self.BASE + 0x1002)
        app, fam = pipeline.nsp.scan_nsps(self.root, ('nsp',), None, base)
        self.assertEqual(app, self.BASE)
        self.assertEqual(fam['updates'], [(262144, update)])
        self.assertEqual(fam['dlc'], [(self.BASE + 0x1002, 0, dlc)])

    def test_the_report_says_where_each_file_was_found(self):
        base = self.dump('Game [v0].nsp', self.BASE)
        self.dump('Update [v262144].nsp', self.BASE + 0x800)
        self.dump('1DLC/DLC [v0].nsp', self.BASE + 0x1002)
        info = pipeline.inspect(base, keys='')
        self.assertEqual([u['folder'] for u in info['updates']], [''])       # next to the base game
        self.assertEqual([d['folder'] for d in info['dlc']], ['1DLC'])        # in the subfolder
        self.assertEqual([d['file'] for d in info['dlc']], ['DLC [v0].nsp'])


if __name__ == '__main__':
    unittest.main()
