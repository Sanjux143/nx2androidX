# The port: a game folder (NSP / XCI, or NSZ / XCZ: porter.dumps) + prod.keys + the game's profile → <App>.apk and <App>-data/ (docs/ARCHITECTURE.md "The port").
# The steps every platform shares are upstream's (porter.upstream.nsp: scan, extraction, the shader split, stage reuse, progress); the Android ones are here:
# the rewrite for DM_TARGET=android, the Vulkan shader bundle, the host built with the player's NDK, the APK, the data pack and the install.
# Work files go where upstream's NSP script puts them, <game folder>/<title ID>_build/ (extract/, aoc/, shader/, logs/ are shared with an iOS build of the same folder),
# with the Android ones in its android/ folder.
import hashlib, importlib.util, mmap, os, re, shutil, socket, struct, subprocess, sys, traceback
from dataclasses import dataclass, field
from pathlib import Path

from porter import config, datapack, dumps, fpslocker, install, paths, profiles, toolchain
from porter.revision import PLAYER_API_REVISION
from porter.adb import NO_ADB, Adb, AdbError
from porter.paths import DM, NXNATIVE
from porter.upstream import BuildError, PROG, android_profile, game, nacp, nsp, profile_guess, say, trinity, uepak

PY = sys.executable
# The overlay's font (Port.ui_font)
UI_FONT = DM / 'third_party/fusion-pixel-font/fusion-pixel-12px-proportional-zh_tw.nxpf'
# The shared font the tools ship: the game's Chinese and Korean text (the console's own shared fonts 1-4; dm/tools/cjk_font.py builds it)
SHARED_CJK = DM / 'tools/shared_cjk.ttf'


def ui_font_assets():   # {name under assets/: file}: the overlay's font and its license files (Port.ui_font; porter host puts them in an APK ported before them)
    d = UI_FONT.parent
    return {'font/ui.bin': UI_FONT, **{f'font/{f.relative_to(d).as_posix()}': f for f in sorted(p for p in d.rglob('*.txt'))}}
# The letters of the button-icon font (make_ext_font.py knocks them out of the icons): a bold system font of the machine that ports, never shipped in the repository
LETTER_FONTS = [f'{os.environ.get("WINDIR", "C:/Windows")}/Fonts/{f}' for f in ('ARLRDBD.TTF', 'arialbd.ttf')] + [
    '/System/Library/Fonts/Supplemental/Arial Rounded Bold.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', '/usr/share/fonts/TTF/DejaVuSans-Bold.ttf',
    # Android's own bold fonts (a phone has no Arial): the letters are knocked out of a solid plate, so any bold font does
    '/system/fonts/Roboto-Bold.ttf', '/system/fonts/DroidSans-Bold.ttf', '/system/fonts/SourceSansPro-Bold.ttf']


@dataclass
class Options:
    folder: Path                       # the game's folder: the base game, its updates and DLC
    keys: Path = None                  # prod.keys
    out: Path = None                   # where the APK and the data pack go (default: the game folder)
    version: str = ''                  # v<N> to pack (default: the newest in the folder)
    language: str = ''                 # a code or a name (default: English)
    app_name: str = ''                 # the launcher label (default: the game's name in its control data)
    app: bool = False                  # the nx2android app's mode: no APK and no install — the pack carries the host (libdmplay.so), the drivers and the icon; the app runs the game itself
    install: bool = False              # install on the device after packing
    device: str = ''                   # adb serial
    replace_signer: bool = False       # uninstall an app of the same package signed with another key
    debug: bool = False                # a development build: debug information, debuggable, DM_CTL
    experimental: bool = False         # a game not ported to Android yet: a provisional profile from upstream's survey (or a copy of its iOS profile), status experimental
    package: str = ''                  # the app's package instead of the profile's (a test install beside another build of the game)
    fps60: bool = False                # 60 fps: the profile's own frame-rate cap, else the build's FPSLocker-Warehouse config (porter/fpslocker.py)
    vulkan_driver: Path = None         # an arm64 Vulkan driver (Mesa's Turnip) packed in the APK and used instead of the system's (DM_VK_DRIVER, dm/android/gfx_vk_core.c)
    vulkan_driver_dir: Path = None     # a folder of arm64 Vulkan drivers: the APK carries them all and the game's launcher lets the player pick one (drivers.txt)
    shared_font: Path = None           # a TrueType font packed as the system's shared fonts, instead of the device's Roboto (DM_SHARED_FONT, dm/host/hle_sdk16.c)
    firmware: Path = None              # the player's system content files (.nca), for the profile's sysdata titles (extract_sysdata; DM_SYSDATA, dm/host/hle_fs.c)
    mt_replay: bool = False            # multithread replay: DM_VK_MT_REPLAY=1 in env.txt (dm/android/gfx_vk_dq.c), after the profile's env rows
    touch: bool = False                # a game played by touch: the on-screen gamepad starts hidden (TOUCH_SETTINGS), so it takes none of the game's touches
    external_display: bool = False     # compile the optional external-screen activity with the Android SDK
    vercode_offset: int = 0            # added to the APK's versionCode: a rebuild of the same game version installs over a build with a higher one (version_code)
    alias_headroom: int = None         # GB more alias region (alias_headroom); None: ALIAS_HEADROOM for a version the profile was not brought up on, none for one it was
    romfs_at: str = None               # with install: the folder on the device the app reads the RomFS from (porter/install.py), 'app' for its files folder; None: as the device has it
    romfs_push: bool = False           # with romfs_at: push the pack's RomFS into that folder first
    jobs: int = field(default_factory=lambda: os.cpu_count() or 8)
    no_lsfg: bool = False              # build the host without the frame generation (the lsfg/ code, dm/android/build.py --no-lsfg): the host is then smaller and the frame generation reports "not in this build"


# The Vulkan driver loader and its four hooks: the host loads a driver through libadrenotools (dm/android/gfx_vk_core.c,
# DM_VK_DRIVER). The loader is the app's own: the host dlopens it by name, and the Android linker looks a bare name up in
# LD_LIBRARY_PATH, in the caller's DT_RUNPATH and in the namespace's default library paths — the app's native library
# folder is the only one of those the app controls (the app's private host folder, where the host itself lives, is none
# of them, so a loader copied there is never found). The hooks travel with the host: it hands the loader the folder it
# sits in, and the loader looks the hooks up there.
# The phone has no C++ compiler, so the loader is built for it here (packaging/android-apk/build_adrenotools.py) and
# shipped in the app's assets; on a computer the host builds it (dm/android/build.py --adrenotools).
ADRENOTOOLS_LOADER = 'libadrenotools.so'
ADRENOTOOLS_HOOKS = ('libhook_impl.so', 'libmain_hook.so', 'libfile_redirect_hook.so', 'libgsl_alloc_hook.so')
ADRENOTOOLS_LIBS = (ADRENOTOOLS_LOADER,) + ADRENOTOOLS_HOOKS


def prebuilt_loader():
    """The driver loader built for the phone (the tool tree's tools/adrenotools), when it is there"""
    d = DM.parent.parent / 'tools' / 'adrenotools'
    return sorted(d.glob('*.so')) if d.is_dir() else []


def launcher_dex():
    """The launcher's dex (the APK's launcher activity, the page the player picks the language and the driver on): built on the computer
    (packaging/android-apk/build_launcher.py) and shipped in the tool tree; None when it was not built (the APK's game is then the entry)"""
    for p in (DM.parent.parent / 'tools' / 'launcher.dex',                                    # the tool tree (the phone, the bundle)
              NXNATIVE.parent / 'packaging/android-apk/.cache/launcher.dex',                 # a developer's checkout
              NXNATIVE.parent / 'packaging/android-apk/.cache/assets/tool/tools/launcher.dex'):  # the built assets
        if p.is_file(): return p
    return None


def driver_lib_name(path):
    """The name --vulkan-driver's library gets in the APK: the package manager extracts only lib*.so from lib/<abi>/, so a driver named otherwise (Mr Purple's
    Turnip T30 is vulkan.purple.so, other Turnip builds vulkan.freedreno.so) never reached the app's library folder, and adrenotools fell back to the system's driver"""
    name = Path(path).name
    return name if name.startswith('lib') else f'lib{name}'


def app_stem(g, name):   # the file name of the APK and the data pack: the profile's iOS IPA name without .ipa (NeoTheWorldEndsWithYou)
    ipa = g.get('ipa') or nsp.ipa_name(name, g.title_id)
    return ipa[:-4] if ipa.endswith('.ipa') else ipa


# --touch: the pause menu's first-launch defaults for a game played by touch. The gamepad's controls take every touch that goes down on them or near them (front_player.c ctl_at,
# overlay_layout.h ov_reach: about a quarter of a tablet's screen, at its sides); hidden, only the menu button does and every other touch is the game's, in both modes (hle_hid.c
# touch_on). As Unpacking's profile (setting overlay=0); the pause menu's Gamepad item shows it again, and a controller works either way
TOUCH_SETTINGS = {'overlay': '0'}

VERCODE_OFFSET_MAX = 65535   # Switch versions are 65536 apart (v65536, v131072): a rebuild of one, offset by this much at most, stays below the next one's port


def version_code(ver, offset=0):
    """The APK's versionCode: the Switch version number (v196608 → 196609), so an update of the game installs over the base game's port. Android installs an APK over one
    with the same versionCode, never over a higher one (INSTALL_FAILED_VERSION_DOWNGRADE): offset (porter port --vercode-offset) lifts a rebuild above a build already on
    the device (an earlier rebuild with an offset, a port of a later version) and keeps its saves, where an uninstall deletes them"""
    if not 0 <= offset <= VERCODE_OFFSET_MAX: raise BuildError(f'the versionCode offset must be 0 to {VERCODE_OFFSET_MAX} (the next game version\'s port must stay above it): {offset}')
    return nsp.vnum(ver) + 1 + offset


ALIAS_HEADROOM, ALIAS_HEADROOM_MAX = 8 << 30, 48   # the automatic headroom (bytes); the most --alias-headroom takes (GB: a 64 GB block with Engage's 5 GB layout)
def alias_headroom_gb(gb):   # --alias-headroom checked: None (automatic) or 0 .. ALIAS_HEADROOM_MAX GB
    if gb is not None and not 0 <= gb <= ALIAS_HEADROOM_MAX: raise BuildError(f'--alias-headroom {gb}: give 0 to {ALIAS_HEADROOM_MAX} GB')
    return gb
ALIAS_HINT = ('if it still crashes after "[RED] [mem] AllocateAddressRegion … refused" (0x1803), port again with a larger --alias-headroom '
              '(the Port tab\'s Extra address space)')


def alias_headroom(tsv, gb=None):
    """A profile's guest block for a game whose profile declares virtual address memory (a sys_resource row, not 0): mem_alias and mem_reserve grow by gb GB (None: ALIAS_HEADROOM,
    which a derived profile gets; the player's --alias-headroom, for any port), so the heap (the rest of the block, hle_svc.c mem_init) stays as it is. Such a game takes its
    memory as address regions in the alias region, and how it does depends on its build: the profile's alias region was sized for the tested build. Donkey Kong Country Returns HD on the Y700 (GitHub issue #26): update 1.1.0 reserves about 1.8 GB of regions, up to
    +2.75 GB of the 4.375 GB alias region; its base game v0 (main 8ac516e8…) first hands pool − used to a StandardAllocator as one 3.14 GB region, which 1.1.0 does not, and then
    reserves Unity's regions above it. In the 4.375 GB region it was refused (0x1803) a few seconds in and crashed at main+0x829630; with 1 GB more it crashed in the opening movie at
    +5.37 GB. Unity frees regions and makes new ones higher up, so the highest address keeps rising above the bytes reserved (4.8 GB): +6.26 GB after the movie, +7.01 GB in 1-1,
    and below +7.25 GB from there to 22 minutes of play with 8 GB more, at 60 fps. A profile without the row (the host then reports 32 MB) is older than the key, and its games set
    a heap; it is left as it is. The Android host reserves the block with MAP_NORESERVE, so the address space costs no memory (Xenoblade Chronicles 3 runs in a 64 GB alias region).
    → (old alias, new alias), or None when the profile is left as it is (gb 0 too)"""
    add = ALIAS_HEADROOM if gb is None else gb << 30
    lines = tsv.read_text(encoding='utf-8').splitlines()
    rows = {l.split('\t', 1)[0]: i for i, l in enumerate(lines) if '\t' in l and not l.startswith('#')}
    val = lambda k, d=None: int(lines[rows[k]].split('\t')[1], 0) if k in rows else d
    if not add or not val('sys_resource', 0) or 'mem_alias' not in rows or 'mem_reserve' not in rows: return None
    alias, reserve = val('mem_alias'), val('mem_reserve')
    lines[rows['mem_alias']], lines[rows['mem_reserve']] = f'mem_alias\t{alias + add:#x}', f'mem_reserve\t{reserve + add:#x}'
    lines.insert(min(rows['mem_alias'], rows['mem_reserve']), f'# porter: {add >> 30} GB more alias region than the tested build\'s ({alias:#x}), and the block with it, for this build\'s address regions')
    tsv.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return alias, alias + add


def registered_patches():
    """{build ID: (the file, [the reason of each patch])} of the files the rewriter loads (patch_nso.py "Registered patches": dm/tools/sdk_patches.py and the profiles' patches.py;
    CUTS and OBS by their reasons, FRAME_STEP as its stubs)"""
    out = {}
    for n, p in enumerate([DM / 'tools/sdk_patches.py'] + sorted((DM / 'games').glob('*/patches.py'))):
        spec = importlib.util.spec_from_file_location(f'porter_patches_{n}', p); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        rel = p.relative_to(DM.parent).as_posix()
        for kind in ('CUTS', 'OBS', 'FRAME_STEP'):
            for bid, v in getattr(m, kind, {}).items(): out.setdefault(bid, (rel, []))[1].extend(['frame step stubs'] if kind == 'FRAME_STEP' else [x[3] for x in v])
    return out


def lost_patches(builds, exefs, mods, table=None):
    """The modules of a derived port whose build has no registered patch while the profile's tested builds of it have some: the rewriter applies a module's patches by its build ID
    only, so the module runs without them. Unpacking's base game v0 ships sdk 2d369d99… (no cuts until GitHub issue #26) where update 2.01's sdk 357efc19… has the node-area and
    1 GB alignment cuts, without which Unity's camera target got no address region and the title was white. → ['<module> build … is not the tested …: …'], one per module"""
    table = registered_patches() if table is None else table
    out = []
    for m in mods:
        if not (exefs / m).is_file(): continue
        bid, tested = nsp.build_id(exefs / m), sorted({b[m] for b in builds.values() if b.get(m)})
        lost = [(t, *table[t]) for t in tested if t in table]
        if bid in tested or bid in table or not lost: continue
        why = '; '.join(f'{f}: ' + ', '.join(dict.fromkeys(r.split(':')[0] for r in rs)) for _, f, rs in lost)
        out.append(f'{m} build {bid[:8]}… is not the tested {", ".join(t[:8] + "…" for t in tested)} and has none of its {sum(len(rs) for _, _, rs in lost)} registered patches ({why})')
    return out


def inspect(folder, keys=None):
    """What a game folder holds, without extracting anything: the GUI's game card and `porter inspect`. keys: prod.keys (default: the configured one), which reads the title ID
    of a dump with neither a ticket nor a [title ID] tag (an untagged cartridge dump) from its NCA headers"""
    p = Path(folder).expanduser()
    base = p if p.is_file() else None
    if base is not None: p = p.parent
    app, fam = nsp.scan_nsps(p, dumps.EXTS, keys or config.load().get('keys'), base)
    g, ap = profiles.by_title(app)
    files = [fam['base'], *(p for _, p in fam['updates']), *(p for _, _, p in fam['dlc'])]

    def where(f):   # the subfolder it was found in ('' when it sits next to the picked file): the GUI's card and the app say where each file came from
        return f.parent.name if f.parent != p else ''

    info = dict(title_id=f'{app:016X}', base=fam['base'].name, updates=[{'version': f'v{v}' if v is not None else None, 'file': f.name, 'folder': where(f)} for v, f in fam['updates']],
                dlc=[{'title_id': f'{t:016X}', 'version': f'v{v}' if v is not None else None, 'file': f.name, 'folder': where(f)} for t, v, f in fam['dlc']], profile=None, android=None, versions=[], pack=None, supported=False, reason='',
                compressed=[p.name for p in files if dumps.compressed(p)])   # NSZ / XCZ: decompressed during the port into the build folder's tmp/, deleted once extracted
    info['experimental'] = not (g and ap)   # a port can still be tried: porter port --experimental
    if not g:
        info['reason'] = f'no game profile for {app:016X}: this game is not supported yet (an experimental port surveys it and guesses a profile; it may not start)'
        return info
    builds = g.builds()
    have = nsp.nsp_versions(fam, builds)
    pack = max(have, key=nsp.vnum)
    info.update(profile=g.id, name=g.get('name', g.id), versions=sorted(have, key=nsp.vnum), pack=pack,
                registered={v: b.get('display', '') for v, b in builds.items()}, derived=pack not in builds)
    if not ap:
        info['reason'] = f'{g.get("name", g.id)} has a game profile (iOS) but is not ported to Android yet (an experimental port tries it with that profile)'
        return info
    info.update(android=profiles.entry(g, ap).as_dict(), supported=True)
    if ap.status == 'wip': info['reason'] = f'{g.get("name", g.id)} is a work in progress: it plays, but parts of it are missing (the Games table in README.md says which)'
    if pack not in builds:
        info['reason'] = (info['reason'] + '; ' if info['reason'] else '') + f'{pack} is not a version the profile was brought up on ({", ".join(builds)}): it is packed from a derived profile, without that version\'s fixes'
    return info


def _has_network(host='api.nuget.org', port=443, timeout=3):
    """Whether the packages can be fetched: a restore that waits for a name server that never answers looks like
    a build that never ends"""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _packages_cached():
    """The NuGet packages the shader translator needs, already in the cache: the restore does not need the network
    then"""
    root = Path(os.environ.get('NUGET_PACKAGES', str(Path.home() / '.nuget' / 'packages')))
    return all((root / p).is_dir() for p in
               ('humanizer', 'msgpack.cli', 'gommon', 'microsoft.io.recyclablememorystream'))


def on_android():
    """The porter running on an Android phone (the phone app's own port): the device is this machine, so the game's own code runs here, with no adb and no second device.
    NX2A_ON_DEVICE forces the answer (1 yes, 0 no), to try the other path on a computer"""
    v = os.environ.get('NX2A_ON_DEVICE')
    if v is not None: return v not in ('0', '', 'no', 'false')
    return Path('/system/build.prop').is_file() or os.environ.get('ANDROID_ROOT') == '/system'


def volume_of(path):
    """The filesystem a path is on, as the device number of its nearest existing folder (None when it cannot be told: Windows).
    Two paths on the same volume can be written in place (a rename), two on different ones cannot"""
    p = Path(path)
    while not p.exists() and p.parent != p: p = p.parent
    try: dev = os.stat(p).st_dev
    except OSError: return None
    return dev or None


def oodle_where(tools):
    """Where the game's own Oodle decoder runs: 'local' when the porter is on the phone app (the device is this machine), 'device' when adb and a device are there, else None"""
    if on_android(): return 'local'
    return 'device' if tools.get('adb') else None


def oodle_job(roms, tmp):
    """Every Oodle entry of the GFPAKs and of the Trinity packs in one job file (DMOJ: u32 count, then per record u32 compressed length, u32 raw length and the compressed bytes).
    → the records (label, archive size, entry index) in order, and the job file. A Trinity file's index is (pack offset, index in the pack)"""
    recs, job = [], tmp / 'job.bin'
    with open(job, 'wb') as jf:
        jf.write(b'DMOJ' + struct.pack('<I', 0))
        for rom in roms:
            for p in sorted(rom.rglob('*.gfpak')):
                d = p.read_bytes()
                if d[:8] != b'GFLXPACK': continue
                n, _, fo = struct.unpack_from('<IIQ', d, 0x10); label = '/' + p.relative_to(rom).as_posix()
                for i in range(n):
                    ct, usz, csz, off = struct.unpack_from('<2xHII4xQ', d, fo + 0x18 * i)
                    if ct == 3: jf.write(struct.pack('<II', csz, usz) + d[off:off + csz]); recs.append((label, len(d), i))
            for p in sorted(rom.rglob('*.trpfs')):
                if (packs := trinity.packs(str(p))) is None: continue
                label = '/' + p.relative_to(rom).as_posix()
                with open(p, 'rb') as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as d:
                    for off, _, _ in packs:
                        for k, _, comp, n, st, sz in trinity.pack_files(d, off):
                            if comp == 3: jf.write(struct.pack('<II', sz, n)); jf.write(d[st:st + sz]); recs.append((label, len(d), (off, k)))
        jf.seek(4); jf.write(struct.pack('<I', len(recs)))
    return recs, job


def oodle_result(tmp, recs, why='', where=''):
    """The decoder's answer (DMOR: u32 count, then per record u32 decoded length — 0 failed, 0xffffffff left out, no BNSH in it — and the decoded bytes): the entries that hold a
    BNSH shader, written back as the split's decoded folders (shader_split.py gfpak_decoded, trpfs_decoded) → the folder. The answer is read in ranges, not as one buffer: a
    4.4 GB job's decoded payloads never all sit in RAM"""
    by = {}   # label → (archive size, {index: (offset in job.out, length)})
    with (tmp / 'job.out').open('rb') as result:
        total = os.fstat(result.fileno()).st_size; head = result.read(8)
        if len(head) != 8 or head[:4] != b'DMOR' or struct.unpack_from('<I', head, 4)[0] != len(recs):
            raise BuildError(f"the game's Oodle decoder {where} answered for other entries than it was given")
        for label, size, i in recs:
            word = result.read(4)
            if len(word) != 4: raise BuildError(f"the game's Oodle decoder {where} returned a truncated result for {label} entry {i}")
            n = struct.unpack('<I', word)[0]
            ent = by.setdefault(label, (size, {}))[1]
            if n == 0xffffffff: continue
            if n == 0: raise BuildError(f"the game's Oodle decoder {where} failed on {label} entry {i}")
            off = result.tell()
            if n > total - off: raise BuildError(f"the game's Oodle decoder {where} returned a truncated result for {label} entry {i}")
            ent[i] = (off, n); result.seek(n, os.SEEK_CUR)
        dec = tmp / 'decoded'; dec.mkdir()
        for label, (size, ent) in by.items():
            trpfs = label.endswith('.trpfs')
            with (dec / (label.lstrip('/').replace('/', '__') + '.dec')).open('wb') as out:
                out.write((b'DTPD' if trpfs else b'DGFD') + struct.pack('<QI', size, len(ent)))
                for i, (off, n) in sorted(ent.items()):
                    out.write(struct.pack('<QII', *i, n) if trpfs else struct.pack('<II', i, n))
                    result.seek(off)
                    while n:
                        chunk = result.read(min(n, 1 << 20))
                        if not chunk: raise BuildError(f"the game's Oodle decoder {where} returned a truncated result for {label} entry {i}")
                        out.write(chunk); n -= len(chunk)
    nsp.rmtree(tmp / 'job.out')
    say(f"shaders in Oodle-compressed archives ({why}): {len(recs):,} entries decoded by the game's own decoder {where}, "
        f"{sum(len(e) for _, e in by.values()):,} of them hold shaders")
    return dec


class Port:
    def __init__(self, opts):
        self.o = opts
        self.cfg = config.load()
        self.result = {}
        self.unpacked = {}   # NSZ / XCZ → its NSP in <build folder>/tmp/dumps/, while the port needs it

    # ---- the whole port ----
    def run(self):
        try: return self._run()
        except (BuildError, KeyboardInterrupt): raise
        except Exception:   # a bug: the whole traceback goes into the build folder's logs, which a bug report zips (the GUI shows only its last line; issue #161 had just "KeyError: 6")
            work = getattr(self, 'work', None)
            if work:
                try:
                    (work / 'logs').mkdir(parents=True, exist_ok=True)
                    (work / 'logs' / 'crash.log').write_text(traceback.format_exc(), encoding='utf-8')
                    say(f'[RED] porter stopped on an error it does not expect; the traceback is in {work / "logs" / "crash.log"} (add it to a bug report)')
                except OSError: pass
            raise

    def _run(self):
        o = self.o
        expected = os.environ.get('NX2A_PLAYER_API_REVISION')
        if expected is not None and expected != str(PLAYER_API_REVISION):
            raise BuildError('The porting environment does not match this app. Refresh the environment before porting.')
        o.folder = Path(o.folder).expanduser().resolve()
        o.game = None
        if o.folder.is_file():    # the game file that was picked: its folder holds the updates and the DLC
            o.game = o.folder
            o.folder = o.folder.parent
        if not o.folder.is_dir(): raise BuildError(f'not a folder: {o.folder}')
        version_code('v0', o.vercode_offset); alias_headroom_gb(o.alias_headroom)   # an offset or headroom out of range fails now, not after the shaders
        if o.vulkan_driver:
            o.vulkan_driver = Path(o.vulkan_driver).expanduser().resolve()
            if not o.vulkan_driver.is_file() or o.vulkan_driver.suffix != '.so': raise BuildError(f'--vulkan-driver: not a .so library: {o.vulkan_driver}')
        if o.vulkan_driver_dir:
            o.vulkan_driver_dir = Path(o.vulkan_driver_dir).expanduser().resolve()
            if not o.vulkan_driver_dir.is_dir(): raise BuildError(f'--vulkan-driver-dir: not a folder: {o.vulkan_driver_dir}')
            if not self.drivers(): raise BuildError(f'--vulkan-driver-dir: no .so library in {o.vulkan_driver_dir}')
        if o.shared_font:
            o.shared_font = Path(o.shared_font).expanduser().resolve()
            if not o.shared_font.is_file() or o.shared_font.suffix.lower() != '.ttf': raise BuildError(f'--shared-font: not a .ttf font: {o.shared_font}')
        if fw := o.firmware or self.cfg.get('firmware'):
            o.firmware = Path(fw).expanduser().resolve()
            if not o.firmware.is_dir(): raise BuildError(f'--firmware: not a folder: {o.firmware}')
        keys = o.keys or self.cfg.get('keys')
        if not keys: raise BuildError('no prod.keys: give -k / --keys, or set it once with `porter config keys <path>`')
        o.keys = Path(keys).expanduser().resolve()
        try: self.tools = toolchain.require(self.cfg, str(o.keys), adb=o.install)
        except toolchain.ToolError as e: raise BuildError(str(e)) from e
        o.out = Path(o.out or self.cfg.get('out') or o.folder).expanduser().resolve()
        # the chosen dotnet first on PATH, in this process too: Windows finds a program by the parent's PATH, not the one passed to the child (upstream runs `dotnet` by name)
        os.environ['PATH'] = os.pathsep.join([str(self.tools['dotnet'].parent), os.environ.get('PATH', '')])
        os.environ['DOTNET_ROOT'] = str(toolchain.dotnet_root(self.tools['dotnet']))   # where nx_extract.exe's apphost looks for the runtime (else the system's, which may lack .NET 10)
        # PYTHONUTF8: the engine's tools write their tables with open() and no encoding, which is the ANSI code page on Windows (shader_split.py's "→" failed)
        self.env = dict(os.environ, J=str(o.jobs), PYTHONIOENCODING='utf-8', PYTHONUTF8='1', DOTNET_CLI_TELEMETRY_OPTOUT='1')
        # The build tools' own libraries (the toolchain's libshaderc for glslc): the tools are run with the environment the
        # guest has, whose library path holds the rootfs's libraries only, and the tools' own RPATH may point elsewhere
        if tc := Path(os.environ.get('NX2A_REPO', '')) / 'toolchain':
            if (tc / 'lib').is_dir():
                self.env['LD_LIBRARY_PATH'] = os.pathsep.join([str(tc / 'lib'), self.env.get('LD_LIBRARY_PATH', '')]).rstrip(os.pathsep)

        try:
            self.inputs()
            PROG.plan(1 + len(self.versions) + bool(self.fam['dlc']) + bool(o.firmware and self.ap.sysdata) + bool(o.fps60) + 6 + bool(o.install))   # nx_extract, the versions, the DLC, the system data, 60 fps, rewrite / split / Vulkan shaders / host / APK / data, install (unpack adds its own)
            self.extract()
        finally: self.drop_unpacked()   # a failed run leaves no decompressed dumps behind either
        self.check_graphics()
        self.fps_unlock()
        self.rewrite()
        if o.install: self.pull_captures()
        self.shaders()
        self.host()
        self.apk()
        self.data()
        if o.install: self.install()
        nsp.rmtree(self.work / 'tmp')
        self.result.update(title_id=self.g.id, name=self.app_name, package=self.package, version=self.pkg_ver, display=self.display, language=self.lang,
                           status=self.ap.status, derived=bool(self.derived), installed=bool(o.install))
        say(f'done: {self.result.get("apk", "no APK (the app runs it)") + ", " if "apk" in self.result else ""}{self.result["data"]}')
        if self.derived: say(f'[RED] {self.derived}: the port is built from a derived profile and may not run as the profile\'s tested version does'
                             + (f'; {self.derived_mem}' if self.derived_mem else ''))
        for x in self.lost: say(f'[RED] {x}')
        return self.result

    # ---- inputs: the folder's games and versions, the profile, the version to pack ----
    def inputs(self):
        o = self.o
        say(f'game folder: {o.folder}' + (f' (game: {o.game.name})' if o.game else ''))
        self.app, self.fam = nsp.scan_nsps(o.folder, dumps.EXTS, o.keys, o.game)
        tid = f'{self.app:016X}'
        # the build folder: where the port's own programs run and where the game is unpacked. On a phone it
        # cannot be next to the game (Android does not let an app run a program on the shared storage), so
        # NX2A_WORK points at the app's own folder.
        root = Path(os.environ.get('NX2A_WORK', '')).expanduser() if os.environ.get('NX2A_WORK') else o.folder
        self.work_root = root
        self.work = root / f'{tid}_build'
        g, ap = profiles.by_title(self.app)
        self.provisional = False
        self.check_space()      # before the survey, which unpacks the game
        if not (g and ap):
            what = f'no game profile for {tid}' if not g else f'{g.get("name", g.id)} ({g.id}) has a game profile but is not ported to Android yet (no android.tsv in {g.dir})'
            if not o.experimental: raise BuildError(f'{what}: --experimental tries it anyway (profiles: `porter profiles`)')
            g, ap = self.experimental_profile(g, tid)
        self.g, self.ap = g, ap
        self.package = o.package or ap.package
        if not re.fullmatch(r'[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+', self.package): raise BuildError(f'not a package name: {self.package}')
        say(f'game profile: {g.id} {g.get("name", "")} (Android: {ap.status}' + (f', tested on {ap.tested}' if ap.tested else '') + ')')
        builds, need = g.builds(), g.words('shader_versions')
        have = nsp.nsp_versions(self.fam, builds)
        self.derived, self.derived_mem, self.lost = '', '', []
        if self.provisional:   # the version the survey saw
            self.pkg_ver = o.version or g.get('package')
            if self.pkg_ver not in builds: raise BuildError(f'the version to pack must be one the provisional profile registers: {", ".join(builds)}')
        else:
            self.pkg_ver = o.version or max(have, key=nsp.vnum)
            if self.pkg_ver not in have: raise BuildError(f'the version to pack ({self.pkg_ver}) is not in the folder, which holds {", ".join(sorted(have, key=nsp.vnum))}')
            if self.pkg_ver not in builds: self.derived = f'{self.pkg_ver} is not a version game profile {g.id} registers ({", ".join(f"{v} {b["display"]}" for v, b in builds.items())})'
            elif not all(v in have for v in need): self.derived = f'the folder has no {", ".join(v for v in need if v not in have)}, whose files the profile\'s shader tables were made from'
            if self.derived: say(f'{self.derived}: packing {self.pkg_ver} from a profile derived from it')
        self.versions = [self.pkg_ver] if self.derived else need + ([self.pkg_ver] if self.pkg_ver not in need else [])
        self.have = have
        self.aw = self.work / 'android'; self.logs = self.work / 'logs'
        for d in (self.aw, self.logs, self.work / 'tmp'): d.mkdir(parents=True, exist_ok=True)
        # DM_GAME picks the profile for every tool; WORK keeps their temporary folders inside the build folder (the shader translator writes thousands of files)
        self.env.update(DM_GAME=g.id, DM_ROOT=str(self.work), WORK=str(self.work / 'tmp'))

    def check_space(self):
        """Stop before extracting when the game folder's drive cannot hold the port: a build folder takes about 1 to 2 times the game files (NEO 7.0 GB of 6.8,
        Viractal 2.7 of 1.4), less what an earlier port left there, plus a margin; an NSZ / XCZ counts as decompressed, and once more for its NSP while it is extracted.
        Only the picked game's files count (its base, its updates, its DLC): the folder may hold other games, which this port never reads.
        The pack is a copy of the game's files (Android's SELinux never lets an app link), so the port needs room for the build's files and for the pack.
        The app's mode copies no RomFS into the pack (the pack points at the build folder's own), so there the build's files are the only copy and the room is half.
        When the pack goes to another volume (the app may put it on the removable card), each volume is asked for its own share"""
        fam = self.fam
        files = [fam['base'], *[p for _, p in fam['updates']], *[p for _, _, p in fam['dlc']]]
        game, temp = sum(dumps.size(f) for f in files), sum(dumps.size(f) for f in files if dumps.compressed(f))
        dlc = sum(dumps.size(p) for _, _, p in fam['dlc'])
        built = sum(f.stat().st_size for f in self.work.rglob('*') if f.is_file()) if self.work.is_dir() else 0
        # the work folder may not be there yet: the space is the one of the folder that will hold it
        where = self.work
        while not where.exists() and where.parent != where: where = where.parent
        margin = 2**30
        copies = 1 if self.o.app else 2   # the app's pack holds no RomFS (it points at the build folder's own): one copy of the game's files, not two
        pack = None
        getter = getattr(self, 'pack_dir_root', None)   # the space check runs before the profile is known
        if getter is not None:
            try: pack = getter()
            except Exception: pack = None
        if pack is not None and volume_of(pack) not in (None, volume_of(where)):
            # the pack goes to another volume (the removable card): the build's files stay here, the pack's copy goes there
            need, free = max(0, game + temp - built) + margin, shutil.disk_usage(where).free
            if free < need: raise BuildError(f'not enough disk space on {where.anchor} for the build folder: the port needs about {need / 2**30:.1f} GB more, {free / 2**30:.1f} GB is free')
            need, free = (dlc if self.o.app else game) + margin, shutil.disk_usage(pack).free
            if free < need: raise BuildError(f'not enough disk space for the data pack in {pack}: the port needs about {need / 2**30:.1f} GB more, {free / 2**30:.1f} GB is free')
            return
        need, free = max(0, copies * game + temp - built) + margin, shutil.disk_usage(where).free
        if free < need: raise BuildError(f'not enough disk space on {where.anchor}: the port needs about {need / 2**30:.1f} GB more, {free / 2**30:.1f} GB is free')

    def use_profile_dir(self, pdir, first):   # every tool finds the game's profile in pdir (game.py DM_GAMES_DIR; first: before dm/games)
        dirs = [str(pdir)] + [d for d in os.environ.get('DM_GAMES_DIR', '').split(os.pathsep) if d and d != str(pdir)]
        self.env['DM_GAMES_DIR'] = os.environ['DM_GAMES_DIR'] = os.pathsep.join(dirs)
        if first: self.env['DM_GAMES_FIRST'] = os.environ['DM_GAMES_FIRST'] = '1'

    def experimental_profile(self, g, tid):
        """A profile for a game not ported to Android, in <build folder>/profile/<id>/ (outside the repository): upstream's survey and provisional profile when the game has
        none (scripts/build_ipa_from_nsp.py survey / write_profile, dm/games/profile_guess.py), or a copy of its iOS profile; plus an android.tsv with status experimental.
        A game brought up this way gets its profile into dm/games by hand (docs/ARCHITECTURE.md "Adding a game")"""
        pdir = self.work / 'profile'; d = pdir / tid
        if g is None:
            if (d / 'game.tsv').is_file():
                nsp.require_64bit(self.o.folder / f'{tid}_survey/extract/ExeFS', f'{tid}')   # a provisional profile an older porter wrote for a 32-bit game (issue #161)
                say(f'no game profile for {tid}: reusing the provisional profile from the last run ({d}; delete it to survey again)')
            else:
                ups = [p for _, p in sorted(self.fam['updates'], key=lambda u: u[0] or 0)[-1:]]   # the survey reads the base game and the newest update
                m = self.unpack([self.fam['base'], *ups])
                fam = dict(self.fam, base=m[self.fam['base']], updates=[(v, m.get(p, p)) for v, p in self.fam['updates']])
                # the survey's scratch is in the port's own folder: the shared storage cannot run programs
                facts = nsp.survey(self.o.folder, self.app, fam, self.o.keys, NXNATIVE, PY, self.env, work_root=self.work)
                facts['survey'] = str(self.work / f'{tid}_survey/survey.md')
                nsp.write_profile(d, self.app, facts)
            self.provisional = True
            say('[RED] provisional game profile: values guessed from the survey, the game may not start')
        elif not (d / 'game.tsv').is_file() or (g.dir / 'game.tsv').stat().st_mtime > (d / 'game.tsv').stat().st_mtime:   # the iOS profile as it is now
            nsp.rmtree(d); shutil.copytree(g.dir, d, ignore=shutil.ignore_patterns('patches.py', '__pycache__'))   # patches.py stays in dm/games, where patch_nso.py finds it
        if not (d / 'android.tsv').is_file():
            (d / 'android.tsv').write_text(f'# Written by porter for an experimental port: the game has not been brought up on Android\nstatus\texperimental\n'
                                          f'package\torg.nx2a.t{tid.lower()}\norientation\tlandscape\n', encoding='utf-8')
        self.use_profile_dir(pdir, first=True)
        g = game.load(tid)
        return g, android_profile.AndroidProfile(g)

    # ---- extract: nx_extract, each version the shaders come from, the DLC ----
    def extract(self):
        o, g = self.o, self.g
        picks = nsp.pick_nsps(self.fam, self.have, self.versions)
        ex = self.work / 'extract'; ex.mkdir(parents=True, exist_ok=True)
        need = sum(dumps.size(p) * (2 if dumps.compressed(p) else 1) for p in {p for b, u in picks.values() for p in (b, u) if p}) / 2**30 + 6
        if nsp.free_gb(self.work) < need and not all((ex / v).exists() for v in self.versions): raise BuildError(f'less than {need:.0f} GB free on the volume of {self.work}')
        # NSZ / XCZ: only what a stage will read is decompressed, all in one stage before nx_extract runs (a version nsp.extract reuses reads nothing)
        fresh = lambda v: (ex / v / 'ExeFS/main').exists() and (ex / v / 'RomFS' / g.get('romfs_marker')).exists()   # nsp.extract's reuse test
        zdlc = [p for _, _, p in self.fam['dlc'] if dumps.compressed(p)]
        # extract_aoc keys its reuse on the names, sizes and times of the NSPs it reads, decompressed ones here: with NSZ / XCZ DLC, porter keys it on those files instead
        dkey = nsp.fingerprint(sorted((t, p.name, p.stat().st_size, p.stat().st_mtime_ns) for t, _, p in self.fam['dlc']),
                               ('files', [NXNATIVE / 'dm/tools/nx_extract' / f for f in ('Program.cs', 'nx_extract.csproj')]))
        dkf = self.work / 'aoc.dumps.key'
        dlc_reused = bool(zdlc) and (self.work / 'aoc').is_dir() and nsp.reused(dkf, dkey) is not None
        m = self.unpack([p for v in self.versions if not fresh(v) for p in picks[v] if p] + ([] if dlc_reused else zdlc))
        picks = {v: tuple(m.get(p, p) if p else None for p in bu) for v, bu in picks.items()}
        PROG.stage('build nx_extract')
        nx = nsp.build_nx_extract(NXNATIVE, self.work, self.logs, self.env)
        PROG.end()
        self.dirs = {ver: nsp.extract(nx, o.keys, ex, ver, *picks[ver], None, self.logs, g) for ver in self.versions}
        for d in self.dirs.values(): nsp.require_64bit(d / 'ExeFS', f'{g.get("name", g.id)} ({g.id})')
        bad = [] if self.derived else [f'{v}: {x}' for v in self.versions for x in nsp.build_mismatch(self.dirs[v], v, g)]
        if bad and self.provisional: raise BuildError(f'not the builds the provisional profile registers: {"; ".join(bad)}')
        if bad:
            self.derived = f'not the builds game profile {g.id} registers: {"; ".join(bad)}'
            say(f'{self.derived}: packing {self.pkg_ver} from a profile derived from it')
            self.versions, self.dirs = [self.pkg_ver], {self.pkg_ver: self.dirs[self.pkg_ver]}
        self.game = self.dirs[self.pkg_ver]
        nsp.rmtree(self.work / 'profile-mem')   # an earlier run's --alias-headroom copy (mem_profile)
        if self.derived:   # the derived profile (android.tsv included) goes into the build folder, and every tool takes it first
            pdir = self.work / 'profile-derived'   # not profile/: an experimental port's copy there is the profile it derives from
            disp = nsp.derive_profile(g, pdir / g.id, self.pkg_ver, self.game, g.words('modules'))
            # automatic headroom only for another build: a registered one, derived because the folder lacks a shader version, keeps the layout it was tested with
            other = self.pkg_ver not in g.builds() or bool(nsp.build_mismatch(self.game, self.pkg_ver, g))
            gb = self.o.alias_headroom if self.o.alias_headroom is not None else (ALIAS_HEADROOM >> 30 if other else 0)
            if grown := alias_headroom(pdir / g.id / 'game.tsv', gb):
                self.derived_mem = f'its alias region has {gb} GB of headroom over the tested build\'s (mem_alias {grown[0]:#x} → {grown[1]:#x}); {ALIAS_HINT}'
                say(f'derived game profile: {self.derived_mem}' + ('' if self.o.alias_headroom is None else ' (--alias-headroom)'))
            self.use_profile_dir(pdir, first=True)
            self.g = game.load(g.id); self.ap = android_profile.AndroidProfile(self.g)
            say(f'derived game profile for {self.pkg_ver} ({disp}): {self.g.dir}')
            self.lost = [f'{x}: the port may fail where they are needed; register them for this build (dm/tools/nso_fnmap.py carries addresses over) or port a version the '
                         f'profile registers' for x in lost_patches(g.builds(), self.game / 'ExeFS', g.words('modules'))]
            for x in self.lost: say(f'[RED] {x}')
        else: self.mem_profile()
        if dlc_reused:
            PROG.stage('extract DLC'); self.aoc = self.work / 'aoc'; PROG.end(f'{len(self.fam["dlc"])} DLC, reused (inputs unchanged)')
        else:
            self.aoc = nsp.extract_aoc(nx, o.keys, NXNATIVE, self.work, self.app, dict(self.fam, dlc=[(t, v, m.get(p, p)) for t, v, p in self.fam['dlc']]), self.logs)
            if zdlc: dkf.write_text(f'{dkey}\n')
            else: nsp.rmtree(dkf)
        self.sysdata = self.extract_sysdata(nx)
        self.drop_unpacked()   # everything that reads them has run, and the shader stages that follow need the room
        self.base = next((self.dirs[v] for v in self.versions if picks[v][1] is None), self.dirs[self.versions[0]])
        self.app_name = o.app_name or nacp.name(self.base / 'control.nacp') or self.g.get('name', self.g.id)
        self.display = nacp.version(self.game / 'control.nacp') or nacp.version(self.base / 'control.nacp') or '1.0'
        lang_in = o.language or self.cfg.get('language')
        want = nsp.language_code(lang_in) if lang_in else 'en-US'
        if not want: raise BuildError(f'unknown game language {lang_in!r}')
        have = nacp.languages(self.game / 'control.nacp') or nacp.languages(self.base / 'control.nacp')
        self.langs = have or ['en-US']
        self.lang = nsp.game_language(want, bool(lang_in), have)
        say(f'app: {self.app_name} {self.display} ({self.package}), game language {self.lang}')

    def mem_profile(self):
        """The player's --alias-headroom on a version the profile was brought up on (or a provisional or experimental profile): a copy of the profile in
        <build folder>/profile-mem/ with the bigger guest block, which every tool takes first. patches.py stays in dm/games, where patch_nso.py finds it. extract() removes
        an earlier run's copy before it, so the build folder keeps none without the option"""
        pdir = self.work / 'profile-mem'
        if not self.o.alias_headroom: return
        d = pdir / self.g.id; shutil.copytree(self.g.dir, d, ignore=shutil.ignore_patterns('patches.py', '__pycache__'))
        grown = alias_headroom(d / 'game.tsv', self.o.alias_headroom)
        if not grown:
            nsp.rmtree(pdir); say(f'--alias-headroom {self.o.alias_headroom}: {self.g.get("name", self.g.id)} declares no virtual address memory (sys_resource): its layout stays as it is'); return
        self.use_profile_dir(pdir, first=True)
        self.g = game.load(self.g.id); self.ap = android_profile.AndroidProfile(self.g)
        say(f'alias region: mem_alias {grown[0]:#x} → {grown[1]:#x} (+{self.o.alias_headroom} GB, --alias-headroom): {self.g.dir}')

    # ---- NSZ / XCZ: decompressed for the stages that read the game files (porter.dumps) into <build folder>/tmp/dumps/, deleted once extracted ----
    def unpack(self, paths):
        """paths' NSZ / XCZ → NSP, in one stage (a file decompressed earlier in this run is kept); returns {path: the file to read}"""
        todo = [p for p in dict.fromkeys(paths) if dumps.compressed(p) and p not in self.unpacked]
        if todo:
            d = self.work / 'tmp/dumps'; d.mkdir(parents=True, exist_ok=True)
            sizes = {p: dumps.size(p) for p in todo}; total, n = sum(sizes.values()), [0]
            if nsp.free_gb(d) < total / 2**30 + 1: raise BuildError(f'decompressing {", ".join(p.name for p in todo)} needs {total / 2**30:.1f} GB on the volume of {d}, which has {nsp.free_gb(d):.1f} GB free')
            PROG.add(1); PROG.stage('decompress NSZ / XCZ', unit='B', total=total)
            def done(k): n[0] += k; PROG.set(done=n[0])
            for p in todo:
                say(f'decompressing {p.name} ({sizes[p] / 2**30:.2f} GB) → {d}')
                self.unpacked[p] = dumps.decompress(p, d / dumps.unpacked_name(p), done)
            PROG.end(f'{len(todo)} file{"s" if len(todo) > 1 else ""}, {nsp.amount(total, "B")}, NCAs checked against their SHA-256 (not in ticketless dumps, whose NCA headers were rewritten)')
        return {p: self.unpacked.get(p, p) for p in paths}

    def drop_unpacked(self):
        if getattr(self, 'work', None): nsp.rmtree(self.work / 'tmp/dumps')
        self.unpacked = {}

    def extract_sysdata(self, nx):
        """The profile's system data titles (android.tsv sysdata), each a RomFS from the player's firmware dump (--firmware) into <work>/sysdata/<title ID>, which the
        data pack carries and the host mounts (DM_SYSDATA, dm/host/hle_fs.c). Firmware content is never shipped with the tool: without a dump the host's stand-in is used
        (MiiModel: Mii heads that draw nothing), and the port says so. Returns the folder, or None"""
        ids = self.ap.sysdata
        out = self.work / 'sysdata'; nsp.rmtree(out)
        if not ids: return None
        need = ', '.join(f'{t:016x}' for t in ids)
        if not self.o.firmware:
            say(f'[RED] {self.g.get("name", self.g.id)} reads system data from the console\'s firmware ({need}); without --firmware <your firmware dump\'s system content '
                f'folder> the host\'s stand-in is used and those parts draw nothing (MiiModel: the Mii heads)')
            return None
        PROG.stage('extract system data')
        for t in ids:
            nsp.run([nx, self.o.keys, '--sysdata', self.o.firmware, f'{t:016x}', out / f'{t:016x}'], self.logs / 'sysdata.log', env=self.env)
        PROG.end(f'{need} from {self.o.firmware}')
        return out

    def check_graphics(self):
        """What the Android host can run: NVN games whose shaders are all in their files. The other kinds need a step upstream runs on a Mac (booting the game in the Mac host)
        or a graphics layer the Android host does not have yet"""
        src = profile_guess.build_sources(self.game / 'ExeFS', self.game / 'RomFS', self.g.words('modules'))
        gfx = src['graphics'][0]
        # OpenGL ES: the device's own GL driver runs it (dm/android/gl_android.c); no shaders to translate, the game compiles its GLSL ES at run time
        self.gl = gfx == 'gl'
        if self.gl: say(f"{self.app_name} renders with OpenGL ({src['graphics'][1]}): the Android host hands its GL calls to the device's OpenGL ES driver"); return
        if gfx != 'nvn': raise BuildError(f'{self.app_name} renders with {gfx} ({src["graphics"][1]}): the Android host replays NVN or OpenGL ES only so far')
        # Game Freak's GFPAKs and Trinity packs with Oodle entries: decoded by the game's own decoder on the Android device (device_oodle), or on this machine when the porter
        # runs on the phone app (local_oodle); PKZ packs still need the Mac host
        self.oodle_why = src['oodle_packs'][1] if src['oodle_packs'][0] and ('GFPAK' in src['oodle_packs'][1] or 'Trinity' in src['oodle_packs'][1]) else ''
        # Unreal paks (version 11) and IoStore containers: their shader code archives are decoded with the game's own Oodle too (device_ue)
        self.ue_why = src['ue_decode'][1] if src['ue_decode'][0] else ''
        why = self.oodle_why or self.ue_why
        self.oodle_run, self.oodle_local, self.oodle_where = None, False, ''
        if why:
            where = oodle_where(self.tools)
            if where == 'local':   # the phone app's porter IS the device: the game's own decoder runs here, with no adb and no second device
                self.oodle_local, self.oodle_where = True, 'here'
            elif where == 'device':
                try: Adb(self.tools['adb'], self.o.device or self.cfg.get('device'), log=say).pick()
                except (AdbError, KeyError) as e: raise BuildError(f"{self.app_name}'s shaders are in Oodle-compressed archives ({why}): the port decodes them with the game's own decoder on "
                                                                   f'the Android device, so connect it (adb) and port again ({e})') from e
                self.oodle_where = 'on the device'
            else:
                raise BuildError(f"{self.app_name}'s shaders are in Oodle-compressed archives ({why}): the port decodes them with the game's own decoder on the Android "
                                 f'device, so it needs adb and the device even without installing. {NO_ADB}; then connect the device (USB debugging on) and port again')
            self.oodle_run = self.device_oodle
        for k in ('glslc_capture', 'ue_legacy') + (() if self.ue_why else ('ue_decode',)) + (() if self.oodle_why else ('oodle_packs',)):
            if src[k][0]: raise BuildError(f'{self.app_name} needs a step that boots the game in the Mac host ({src[k][1]}), which the Android port cannot run yet')

    # ---- the game's own Oodle decoder, for the shader split (Pokémon Legends: Arceus's GFPAKs, Pokémon Legends: Z-A's Trinity packs) ----
    ODEV = '/data/local/tmp/nx2a-oodle'   # dmhost, the rewritten modules and the job, removed afterwards
    def oodle_host(self):
        """The host built as a tool (build.py --exe): the game's own modules and the game's own OodleLZ_Decompress, run without a window. It lands in the host stage's own
        folder, so its objects serve both → the executable"""
        out = self.aw / ('host-debug' if self.o.debug else 'host')   # the host stage's own folder: its objects serve both
        PROG.set(step="building the host as a tool for the game's Oodle decoder")
        nsp.run([PY, DM / 'android/build.py', '--game', self.g.id, '--ndk', self.tools['ndk'], '--out', out, '--exe', '--jobs', str(self.o.jobs)] + ([] if self.o.debug else ['--release'])
                + (['--no-lsfg'] if self.o.no_lsfg else []),
                self.logs / 'android_oodle_host.log', env=self.env)
        return out / 'dmhost'
    def device_oodle(self):
        """The game's own Oodle decoder decodes the Oodle entries of the GFPAKs and of the Trinity packs (Pokémon Legends: Arceus, Pokémon Legends: Z-A): on the Android device
        (adb), or on this machine when the porter runs on the phone app (the device is this machine). Every entry goes in one job file (1.6 GB for Pokémon Legends: Arceus,
        4.4 GB for Pokémon Legends: Z-A's 223,018 files), decoded in one run; only the entries that hold a BNSH shader come back (DM_OODLE_JOB_BNSH), written as the split's
        decoded folder (shader_split.py gfpak_decoded, trpfs_decoded). Streaming the entries through an adb forward instead reset the connection whenever both directions
        were busy.
        → the split's environment"""
        PROG.set(step="the game's Oodle entries in a job file")
        where = getattr(self, 'oodle_where', '') or 'on the device'
        tmp = self.work / 'tmp/oodle'; nsp.rmtree(tmp); tmp.mkdir(parents=True)
        recs, job = oodle_job([self.dirs[v] / 'RomFS' for v in self.versions], tmp)
        PROG.set(step=f"the game's Oodle decoder {where}: {len(recs):,} entries, {job.stat().st_size / 2**30:.1f} GB")
        self.oodle_job(job, tmp / 'job.out', bnsh=True)
        return {'DM_GFPAK_DECODED': str(oodle_result(tmp, recs, self.oodle_why, where))}
    def oodle_job(self, job, out, bnsh=False):
        """One Oodle job file (hle_oodle.c: "DMOJ" records) decoded into out ("DMOR"); the job file is removed. On the device the host runs as an executable and the rewritten
        modules go there once a port (device_oodle_end removes them); here it runs on this machine (the phone app's porter IS the device).
        bnsh: only the entries that hold a BNSH shader come back (DM_OODLE_JOB_BNSH)"""
        if getattr(self, 'oodle_local', False): return self.local_oodle_job(job, out, bnsh)
        return self.device_oodle_job(job, out, bnsh)
    def device_oodle_job(self, job, out, bnsh=False):
        """The job, decoded on the device: the host as an executable and the rewritten modules go to the device once a port (device_oodle_end removes them)"""
        adb = getattr(self, 'oodle_adb', None)
        if not adb:
            adb = Adb(self.tools['adb'], self.o.device or self.cfg.get('device'), log=say); adb.pick()
            host = self.aw / ('host-debug' if self.o.debug else 'host')   # the host stage's own folder: its objects serve both
            PROG.set(step="building the host as a tool for the game's Oodle decoder")
            nsp.run([PY, DM / 'android/build.py', '--game', self.g.id, '--ndk', self.tools['ndk'], '--out', host, '--exe', '--jobs', str(self.o.jobs)] + ([] if self.o.debug else ['--release']),
                    self.logs / 'android_oodle_host.log', env=self.env)
            adb.shell(f'rm -rf {self.ODEV}; mkdir -p {self.ODEV}/mods', check=False)
            adb.push_file(host / 'dmhost', f'{self.ODEV}/dmhost'); adb.push_tree(self.patched, f'{self.ODEV}/mods')
            self.oodle_adb = adb
        adb.push_file(job, f'{self.ODEV}/job.bin'); nsp.rmtree(job)
        # (the device's timeout ends the decoder before adb gives up, so no decoder is left running and holding memory on the device)
        r = adb.shell(f'chmod 755 {self.ODEV}/dmhost; cd {self.ODEV} && rm -f job.bin.out && DM_OODLE_JOB={self.ODEV}/job.bin{" DM_OODLE_JOB_BNSH=1" if bnsh else ""} timeout 3300 ./dmhost mods '
                      f'> job.log 2>&1; echo "exit $?"; grep -a -e DM_OODLE_JOB -e RED job.log | tail -5', timeout=3600)
        (self.logs / 'android_oodle_job.log').write_text(r, encoding='utf-8')
        if 'exit 0' not in r: raise BuildError(f"the game's Oodle decoder on the device failed (see {self.logs / 'android_oodle_job.log'})")
        adb.run('pull', f'{self.ODEV}/job.bin.out', str(out), timeout=1800)
        adb.shell(f'rm -f {self.ODEV}/job.bin {self.ODEV}/job.bin.out', check=False)
    def local_oodle_job(self, job, out, bnsh=False):
        """The same job, decoded here: the phone app's porter IS the device (Android, arm64), so the game's own code runs on this machine — no adb, no second device. The host
        built as an executable (build.py --exe) takes the job file and the rewritten modules, decodes with the game's own OodleLZ_Decompress (the same ARM64 code the Switch
        runs) and writes the answer beside the job file → out"""
        dmhost = getattr(self, 'oodle_host_path', None)
        if not dmhost:
            dmhost = self.oodle_host(); self.oodle_host_path = dmhost
        os.chmod(dmhost, 0o755)   # a file the App unpacked is not executable: the phone's own exec bit
        env = dict(self.env, DM_OODLE_JOB=str(job))
        if bnsh: env['DM_OODLE_JOB_BNSH'] = '1'
        env.pop('LD_LIBRARY_PATH', None)   # the guest's glibc libraries are none of the bionic host's business: its own are in /system/lib64
        try:
            r = subprocess.run([str(dmhost), str(self.patched)], env=env, capture_output=True, text=True, errors='replace', timeout=3600)
        except subprocess.TimeoutExpired:
            raise BuildError(f"the game's Oodle decoder took more than an hour and was stopped (see {self.logs / 'android_oodle_job.log'})")
        (self.logs / 'android_oodle_job.log').write_text((r.stdout or '') + (r.stderr or ''), encoding='utf-8')
        if r.returncode != 0: raise BuildError(f"the game's Oodle decoder failed (see {self.logs / 'android_oodle_job.log'})")
        answer = job.with_name(job.name + '.out')   # the host writes the answer beside the job file
        if not answer.is_file(): raise BuildError(f"the game's Oodle decoder wrote no answer (see {self.logs / 'android_oodle_job.log'})")
        shutil.move(str(answer), str(out))
        nsp.rmtree(job)   # the job file is up to 4.4 GB: the phone has no room to keep it

    # ---- Unreal Engine shader code archives decoded with the game's own Oodle (the Mac does it in decode_ue_shaders) ----
    UE_ROUNDS = 4
    def device_ue(self):
        """Unreal Engine keeps its shaders in shader code archives that only the game's own Oodle decodes: in its paks (version 11; each shader Oodle-compressed, Octopath Traveler 0), or
        in IoStore containers (Unreal Engine 5.6, FINAL FANTASY RESONANCE: shader libraries whose shader groups are Oodle-compressed as wholes, 5,919 groups in 64 KB container blocks
        that are stored). uepak.job reads both through a decoder it calls one stream at a time; on the Mac that is the host over a pipe (OodleHost), which adb cannot carry (its stdin is
        not binary-safe on Windows, and a forward reset when both directions were busy). Here it runs in rounds instead: uepak.job runs with a decoder that answers from the
        streams decoded so far and notes the others (answering zeros, so a stream that depends on one noted is noted only in a later round, with its real bytes, and a read that
        fails on the zeros ends the round); the noted ones go to the device as one job (oodle_job), and the next round runs. A round that notes nothing has decoded everything;
        its per-shader Oodle job (paks) goes to the device as well, and the decoded archives land in <version dir>/ue_shaders, where the split reads them, with the NSP script's stamp.
        The streams a round decoded are written to <version dir>/ue_streams/<the paks' digest>, one file each, so a run that stops (the phone's memory, the app killed) continues with
        the streams it has and asks the decoder for the rest only"""
        import hashlib
        import struct
        todo = {v: self.dirs[v] for v in self.versions if not ((self.dirs[v] / 'ue_shaders/stamp').exists() and (self.dirs[v] / 'ue_shaders/stamp').read_text() == nsp.ue_stamp(self.dirs[v]))}
        if not todo: return
        PROG.add(1); PROG.stage('UE shader decode', step='the shader code archives')
        tmp = self.work / 'tmp/ue'; total = 0
        for v, d in todo.items():
            jd = tmp / f'job_{v}'
            sdir = d / 'ue_streams' / hashlib.sha1(nsp.ue_stamp(d).encode()).hexdigest()[:16]   # the streams decoded so far, on disk
            sdir.mkdir(parents=True, exist_ok=True)
            for old in (d / 'ue_streams').iterdir():
                if old != sdir: nsp.rmtree(old)   # another pak set's streams are of no use
            for rnd in range(self.UE_ROUNDS):
                want = {}
                def dec(comp, n):
                    k = (hashlib.sha1(comp).digest(), n)
                    f = sdir / f'{k[0].hex()}-{k[1]}'
                    if f.is_file():
                        b = f.read_bytes()
                        if len(b) == n: return b   # decoded in an earlier run
                    want.setdefault(k, bytes(comp)); return bytes(n)
                nsp.rmtree(jd); PROG.set(step=f'{v}: the shader code archives (round {rnd + 1})')
                try: listing, n = uepak.job(str(d / 'RomFS'), str(d / 'ExeFS/main'), str(jd), decode=dec)
                except (ValueError, struct.error, IndexError) as e:
                    if not want: raise BuildError(f'{v}: the Unreal shader code archives do not read: {e}') from e
                if not want: break
                job, out = tmp / 'round.job', tmp / 'round.out'
                with open(job, 'wb') as f:
                    f.write(b'DMOJ' + struct.pack('<I', len(want)))
                    for (_, raw), comp in want.items(): f.write(struct.pack('<II', len(comp), raw) + comp)
                PROG.set(step=f"{v}: {len(want):,} Oodle streams with the game's decoder {self.oodle_where} (round {rnd + 1})")
                self.oodle_job(job, out)
                b = out.read_bytes(); nsp.rmtree(out)
                if b[:4] != b'DMOR' or struct.unpack_from('<I', b, 4)[0] != len(want): raise BuildError("the game's Oodle decoder answered for other streams than it was given")
                o, got = 8, 0
                for k in want:
                    ln = struct.unpack_from('<I', b, o)[0]; o += 4
                    if ln == k[1]: (sdir / f'{k[0].hex()}-{k[1]}').write_bytes(b[o:o + ln]); got += 1   # a stream decoded from zeros fails (0) and is asked for again with its real bytes
                    o += ln if ln != 0xffffffff else 0
                say(f'{v}: round {rnd + 1}: {got:,} of {len(want):,} Oodle streams decoded {self.oodle_where}')
                if not got: raise BuildError(f"{v}: the game's Oodle decoder decoded none of {len(want):,} streams (see {self.logs / 'android_oodle_job.log'})")
            else: raise BuildError(f'{v}: the Unreal shader code archives still need Oodle streams after {self.UE_ROUNDS} rounds')
            if not listing: raise BuildError(f'the RomFS has Unreal paks, but none under {d / "RomFS"} holds a shader code archive: where the shaders are has to be found first')
            if n:   # Oodle-compressed shaders in the paks' archives (Octopath Traveler 0): uepak.job's own job file
                PROG.set(step=f"{v}: {n:,} shaders with the game's decoder {self.oodle_where}")
                self.oodle_job(jd / 'oodle.job', jd / 'oodle.job.out')   # (finish reads job.json and the output only)
            out = d / 'ue_shaders.partial'; nsp.rmtree(out)
            uepak.finish(str(jd), str(out)); (out / 'stamp').write_text(nsp.ue_stamp(d))
            nsp.swap_in(out, d / 'ue_shaders'); nsp.rmtree(jd)
            nsp.rmtree(d / 'ue_streams')   # the decode is done: the streams on disk are of no use
            say(f'{v}: ' + '; '.join(f'{x["name"]} {x["shaders"]:,} shaders' + (f' ({x["compression"]})' if x.get('compression') else '') for x in listing))
            total += n + sum(x['shaders'] for x in listing if x['compression'] == 'IoStore, decoded')
        nsp.rmtree(tmp)
        PROG.end(f'{total:,} shaders decoded {self.oodle_where}')
    def device_oodle_end(self):
        adb = getattr(self, 'oodle_adb', None)
        if adb:
            self.oodle_adb = None
            try: adb.shell(f'rm -rf {self.ODEV}', check=False)
            except AdbError as e: say(f'the Oodle decoder left on the device ({e}): {self.ODEV}')
        if getattr(self, 'oodle_local', False): nsp.rmtree(self.work / 'tmp/oodle')

    # ---- 60 fps (--fps60): the profile's own cap (fps_target: frame-step stubs or hle_fpslock.c's rows), else the community's FPSLocker config of the build ----
    def fps_unlock(self):
        self.fps_env, self.fps_files = [], {}
        self.env.pop('DM_PATCHES_EXTRA', None)
        if not self.o.fps60: return
        PROG.stage('60 fps', step='the game profile')
        if self.g.get('fps_target', '0') != '0':
            self.fps_env = ['DM_FPS_TARGET=60']; PROG.end('the game profile\'s own frame-rate cap (fps_target), at 60'); return
        img, bid = fpslocker.nso_image(self.game / 'ExeFS/main')
        src = fpslocker.url(self.g.id, bid); d = self.aw / 'fpslock'; d.mkdir(exist_ok=True); cache = d / f'{bid[:16].upper()}.yaml'
        PROG.set(step='fetching the FPSLocker-Warehouse config')
        try: text = fpslocker.fetch(self.g.id, bid)
        except OSError as e:
            if not cache.is_file(): say(f'[RED] 60 fps: {src} could not be fetched ({e}): no FPSLocker config applied; built-in profile patches are unaffected'); PROG.end('external config not fetched; built-in patches unaffected'); return
            text = cache.read_text(encoding='utf-8'); say(f'[RED] 60 fps: {src} could not be fetched ({e}): using the copy fetched before')
        if text is None:
            nsp.rmtree(cache); say(f'60 fps: FPSLocker-Warehouse has no config for main build {bid[:16].upper()}: no FPSLocker config applied; built-in profile patches are unaffected'); PROG.end('no external config for this build; built-in patches unaffected'); return
        cache.write_text(text, encoding='utf-8')
        try: tsv, cuts = fpslocker.convert(text, img, bid, src)
        except fpslocker.Refused as e: say(f'[RED] 60 fps: {src} cannot be applied ({e}): no FPSLocker config applied; built-in profile patches are unaffected'); PROG.end('external config not applicable; built-in patches unaffected'); return
        (d / 'fpslock.tsv').write_text(tsv, encoding='utf-8', newline='\n')
        if cuts:   # code bytes: the rewriter's cuts, applied before it rewrites main (patch_nso.py DM_PATCHES_EXTRA; part of the rewrite's reuse key)
            (d / 'patches.py').write_text(fpslocker.patches_py(bid, cuts, src), encoding='utf-8', newline='\n'); self.env['DM_PATCHES_EXTRA'] = str(d / 'patches.py')
        # FPSLocker paces the presents for its target itself, whatever interval the game asks for, so its configs leave the game's interval alone: presents follow the display (present_interval display)
        self.fps_env = ['DM_FPS_TARGET=60', f'DM_FPS_LOCKS={datapack.files_folder(self.package)}/fpslock.tsv', 'DM_PRESENT_INTERVAL=display']
        self.fps_files = {'fpslock.tsv': d / 'fpslock.tsv'}
        n = tsv.count('\n') - 3
        say(f'60 fps: FPSLocker-Warehouse config {src}, by masagrator and contributors: {n} memory write(s)' + (f', {len(cuts)} code cut(s)' if cuts else ''))
        PROG.end(f'{n} memory write(s)' + (f', {len(cuts)} code cut(s)' if cuts else ''))

    # ---- rewrite: patch_nso.py for the Android host's thread slots ----
    def rewrite(self):
        self.patched = self.aw / f'patched_{self.pkg_ver}'   # beside, not over, an iOS build's patched_<version>: the rewrite's reuse key does not include the target
        nsp.rewrite(PY, NXNATIVE, self.game / 'ExeFS', self.patched, dict(self.env, DM_TARGET='android'), self.logs, self.g)
        if self.derived or self.provisional:   # the keys that need the rewritten modules (upstream's provisional steps)
            nsp.provisional_prep(PY, NXNATIVE, self.env, self.logs, self.g)
            self.g = nsp.refine_after_rewrite(self.g, self.game, self.patched)
            if self.provisional and profile_guess.heap_check_needed(self.game / 'ExeFS') and not nsp.heap_checked(self.g):
                say('[RED] no heap check: it boots the game in the Mac host; the provisional profile keeps its guessed heap keys')

    # ---- shaders: upstream's split, then SPIR-V for the Vulkan backend ----
    def shaders(self):
        if getattr(self, 'gl', False):   # an OpenGL ES game: the driver compiles its shaders on the device
            self.dmsv = None
            for st in ('shader split', 'Vulkan shaders'): PROG.stage(st); PROG.end('none: the game renders with OpenGL ES')
            return
        sv = self.versions
        pkg = self.work / 'shader' / 'pkg'
        try:
            if getattr(self, "ue_why", ""): self.device_ue()   # Unreal's shader code archives, decoded with the game's own Oodle (on the device, or here on the phone app) into <version dir>/ue_shaders before the split reads them
            nsp.split_shaders(PY, NXNATIVE, [self.dirs[v] / 'RomFS' for v in sv], [self.dirs[v] / 'ExeFS/main' for v in sv], pkg, self.env, self.logs, self.g, bool(self.derived or self.provisional),
                               oodle=self.oodle_run,   # Oodle-compressed archives: the game's own decoder (on the device, or here on the phone app)
                               aoc=self.aoc)   # DLC with shaders of its own (Xenoblade Chronicles 3's archives)
        finally: self.device_oodle_end()
        self.dmsv = self.aw / 'shaders.dmsv'; kf = self.aw / 'shaders.dmsv.key'; cap = self.aw / 'captured_shaders'
        PROG.stage('Vulkan shaders', step='building the translator')
        xlate, xkey = self.translator()
        PROG.set(step='checking the inputs')
        key = nsp.fingerprint(nsp.package_key(pkg), ('files', [DM / 'android/tools/shader_vk.py', DM / 'android/tools/dmsv.py'] + nsp.tool_files(NXNATIVE)), ('files', nsp.profile_files(self.g)), xkey,
                              ('files', sorted(cap.glob('*.bin'))))
        if self.dmsv.is_file() and (note := nsp.reused(kf, key)) is not None:
            PROG.end(f'{note}, reused (inputs unchanged)'); return
        tmp = self.dmsv.with_name('shaders.dmsv.partial'); nsp.rmtree(tmp); nsp.rmtree(kf)
        def line(s):
            if m := re.match(r'(translating|compressing) (\d+) shaders', s): PROG.set(step=f'{m.group(1)} {int(m.group(2)):,} shaders')   # compressing: the bundle's SPIR-V (dmsv.py)
            elif s.startswith('[RED] this Python has no zstd'): say(s)   # the bundle then goes uncompressed: 3.5x the space on the device
        nsp.run([PY, DM / 'android/tools/shader_vk.py', pkg, tmp, '--jobs', str(self.o.jobs), '--dotnet', self.tools['dotnet'], '--xlate', xlate, '--capture', cap],
                self.logs / 'android_shaders.log', env=self.env, on_line=line)
        m = re.search(r'^(\d+) of (\d+) shaders', (self.logs / 'android_shaders.log').read_text(errors='replace'), re.M)
        if not m or m.group(1) == '0': raise BuildError(f'no shader translated (see {self.logs / "android_shaders.log"})')
        note = f'{int(m.group(1)):,} of {int(m.group(2)):,} shaders, {tmp.stat().st_size / 2**20:,.0f} MB'
        nsp.swap_in(tmp, self.dmsv); kf.write_text(f'{key}\n{note}\n')
        PROG.end(note)
        for d in (self.work / 'tmp').glob('shvk_*'): nsp.rmtree(d)   # the translator's work folders

    def pull_captures(self):
        """Shaders whose code no game file holds: the app saves each one the bundle lacks on the device (gfx_vk_shader.c vk_shader_missing), and they are
        pulled here into <build folder>/android/captured_shaders, kept from port to port, for shader_vk.py to translate into the bundle (Xenoblade Chronicles 3: effect and model shaders).
        Only for an install, which has the device; a device that cannot be reached leaves the captures already pulled"""
        adb = Adb(self.tools['adb'], self.o.device or self.cfg.get('device'), log=say)
        try:
            adb.pick()
            got = adb.pull_new(f'{datapack.files_folder(self.package)}/captured_shaders', self.aw / 'captured_shaders', pattern=lambda n: re.fullmatch(r'[0-9a-f]{16}\.bin', n))
        except AdbError as e: say(f'shaders captured on the device not pulled ({e})'); return
        if got: say(f'{len(got)} shader(s) missing from the bundle, captured on the device: {" ".join(n[:16] for n in got)}')

    def translator(self):
        """The shader translator (dm/gfx/tools/dmsh_xlate over dm/third_party/ryujinx) built from the repository's source with the player's .NET SDK, once for every game,
        in <user dir>/tools/dmsh_xlate: (dmsh_xlate.dll, the source's key). Not the iOS chain's prebuilt dm/gfx/tools/dmsh_xlate_bin, which only a Mac can rebuild
        (its lock fingerprints Apple's Metal compiler): Android takes a translator fix as soon as it is in the source.
        A build made for the phone (NX2A_DMSH_XLATE) is used as it is: the phone has no SDK to restore the packages with (no network) and its NuGet settings
        read never ends, so the phone does not build the translator but runs one that was built for it"""
        pre = os.environ.get('NX2A_DMSH_XLATE') or (NXNATIVE.parent / 'tools' / 'dmsh_xlate')   # the build shipped with the phone
        if not Path(pre).is_dir() and not _has_network() and not _packages_cached():
            # without the prebuilt the translator has to be built here, and the restore needs the network: on the
            # phone that is a wait of minutes for a name server that never answers
            raise BuildError('the prebuilt shader translator is missing and the packages cannot be restored '
                             '(no network): the app was built without the C# tools (build_nx_extract.py needs '
                             'the .NET SDK)')
        if Path(pre).is_dir():
            src = Path(pre)
            if not (src / 'dmsh_xlate.dll').is_file(): raise BuildError(f'NX2A_DMSH_XLATE has no dmsh_xlate.dll: {src}')
            out = paths.user_dir() / 'tools/dmsh_xlate'; nsp.rmtree(out); shutil.copytree(src, out)
            key = nsp.fingerprint(('stat', out), ('cmd', ['dmsh_xlate', 'prebuilt']), env=self.env)
            return out / 'dmsh_xlate.dll', key
        src = DM / 'gfx/tools/dmsh_xlate'; ryu = DM / 'third_party/ryujinx'
        files = sorted([*src.glob('*.cs'), *src.glob('*.csproj'), *ryu.glob('*.props')] + [p for p in (ryu / 'src').rglob('*') if p.suffix in ('.cs', '.csproj', '.props')
                                                                                         and not {'bin', 'obj'} & set(p.relative_to(ryu).parts)])
        key = nsp.fingerprint(('files', files), ('cmd', [str(self.tools['dotnet']), '--version']), env=self.env)
        out = paths.user_dir() / 'tools/dmsh_xlate'; kf = out.with_name('dmsh_xlate.key'); dll = out / 'dmsh_xlate.dll'
        if dll.is_file() and nsp.reused(kf, key) is not None: return dll, key
        tmp = out.with_name('dmsh_xlate.partial'); nsp.rmtree(tmp); nsp.rmtree(kf)
        nsp.run([self.tools['dotnet'], 'build', src, '-c', 'Release', '-o', tmp, '-nologo', '-v', 'quiet'], self.logs / 'android_translator_build.log', env=self.env)
        if not (tmp / 'dmsh_xlate.dll').is_file(): raise BuildError(f'the translator built without dmsh_xlate.dll (see {self.logs / "android_translator_build.log"})')
        nsp.swap_in(tmp, out); kf.write_text(f'{key}\n')
        return dll, key

    # ---- host: the Android host built for this profile with the player's NDK ----
    def host(self):
        PROG.stage('host', step=f'NDK {self.tools["ndk"].name}')
        out = self.aw / ('host-debug' if self.o.debug else 'host')
        # the app's mode runs the game itself: libdmplay.so (the host with the player's JNI entry) instead of the APK's libdmhost.so
        nsp.run([PY, DM / 'android/build.py', '--game', self.g.id, '--ndk', self.tools['ndk'], '--out', out,
                 '--play' if self.o.app else '--lib', '--jobs', str(self.o.jobs)] + ([] if self.o.debug else ['--release'])
                + (['--no-lsfg'] if self.o.no_lsfg else [])
                + (['--adrenotools'] if not prebuilt_loader() else []), self.logs / 'android_host.log', env=self.env)
        self.so = out / ('libdmplay.so' if self.o.app else 'libdmhost.so')
        if not self.so.is_file(): raise BuildError(f'build.py made no {self.so}')
        ph = out / 'gen' / self.g.id / 'game_profile.h'   # the profile the host was built from, for the data pack (porter host compares it with its checkout's)
        self.profile_h = hashlib.sha256(ph.read_bytes()).hexdigest() if ph.is_file() else None
        PROG.end(f'{self.so.stat().st_size / 2**20:.1f} MB')

    # ---- apk: the host as a NativeActivity app, signed with the player's key ----
    def apk(self):
        if self.o.app:   # the app's mode: the pack is the only artifact, the app runs the game itself
            self.result['mode'] = 'app'
            return
        PROG.stage('APK')
        stem = app_stem(self.g, self.app_name)
        self.apk_path = self.o.out / f'{stem}.apk'; self.o.out.mkdir(parents=True, exist_ok=True)
        icon = self.icon()
        code = version_code(self.pkg_ver, self.o.vercode_offset)
        if self.o.vercode_offset: say(f'versionCode {code} ({self.pkg_ver} + 1 + offset {self.o.vercode_offset})')
        external = self.external_activity() if self.o.external_display else []
        driver = self.o.vulkan_driver
        if driver and driver.name != driver_lib_name(driver):   # packed under a lib*.so name (driver_lib_name), copied beside the host library
            driver = self.so.parent / driver_lib_name(driver); shutil.copyfile(self.o.vulkan_driver, driver)
            say(f'--vulkan-driver: {self.o.vulkan_driver.name} is packed as {driver.name} (Android extracts only lib*.so libraries)')
        nsp.run([PY, DM / 'android/apk/mkapk.py', self.so, self.apk_path, paths.signing_key(), '--package', self.package, '--label', self.app_name, '--lib', 'dmhost',
                 '--data-root', str(self.data_dir()),
                 '--version-code', str(code), '--version-name', self.display]
                + (['--landscape'] if self.ap.landscape else []) + icon + self.theme() + self.ui_font() + external + ([] if self.o.debug else ['--release'])
                + [a for f in dict.fromkeys(str(f) for f in (self.drivers() + self.loader_libs() + self.loader_so()
                                                             + ([driver] + [self.so.parent / n for n in ADRENOTOOLS_LIBS] if driver else []))) for a in ('--extra-so', f)]
                + (['--dex', str(dex)] if (dex := launcher_dex()) else []),
                self.logs / 'android_apk.log', env=self.env)
        self.result['apk'] = str(self.apk_path)
        PROG.end(f'{self.apk_path.name}, {self.apk_path.stat().st_size / 2**20:.1f} MB')

    def external_activity(self):
        javac = shutil.which('javac')
        roots = toolchain._sdk_roots()
        platforms = [p for root in roots for p in (root / 'platforms').glob('android-*/android.jar')]
        d8s = [p for root in roots for p in (root / 'build-tools').glob('*/d8' + ('.bat' if os.name == 'nt' else ''))]
        def version(p): return tuple(int(x) for x in re.findall(r'\d+', p.parent.name))
        if not javac or not platforms or not d8s:
            raise BuildError('--external-display needs javac and an Android SDK with a platform and build-tools (set ANDROID_HOME)')
        platform, d8 = max(platforms, key=version), max(d8s, key=version)
        out = self.aw / 'external-activity'; classes, dex = out / 'classes', out / 'dex'
        shutil.rmtree(out, ignore_errors=True); classes.mkdir(parents=True); dex.mkdir()
        sources = sorted((DM / 'android/java/org/nx2a').glob('*.java'))
        nsp.run([javac, '-source', '8', '-target', '8', '-classpath', platform, '-d', classes, *sources], self.logs / 'android_external_java.log', env=self.env)
        nsp.run([d8, '--min-api', '29', '--lib', platform, '--output', dex, *sorted(classes.rglob('*.class'))], self.logs / 'android_external_dex.log', env=self.env)
        return ['--dex', dex / 'classes.dex', '--activity', 'org.nx2a.ExternalActivity']

    def theme(self):
        """The on-screen gamepad's theme: only the game's own pack goes in the APK (dm/android/themes/themes.tsv maps title IDs to packs; a game with no row
        gets the ergo pack), as assets/overlay/ (theme.tsv and an atlas per layout), which the app reads at its first frame (dm/android/overlay_theme.c)"""
        files = self.theme_files()
        if not files: return []
        self.result['theme'] = android_profile.theme(self.g.id).name; say(f'gamepad theme: {self.result["theme"]}')
        return [a for name, src in files.items() for a in ('--asset', f'{name}={src}')]

    def theme_files(self):
        """The theme's files ({name in the pack: source}, overlay/ of the pack), or None without them. The APK carries them as assets/overlay/
        (theme()); the player's app has no APK assets, and its pack carries them as plain files (overlay_theme.c falls back to the pack)"""
        pack = android_profile.theme(self.g.id)
        files = [pack / 'theme.tsv'] + [pack / f'{lay}.png' for lay in android_profile.LAYOUTS]
        if missing := [f.name for f in files if not f.is_file()]:
            say(f'[RED] the gamepad theme {pack.name} has no {", ".join(missing)}: the app draws its gamepad without a theme'); return None
        return {f'overlay/{f.name}': f for f in files}

    def ui_font(self):
        """The overlay's font (dm/android/overlay_theme.c dm_overlay_font) and, as its license asks, the license files beside it, as assets/font/"""
        return [a for n, f in ui_font_assets().items() for a in ('--asset', f'{n}={f}')]

    def icon(self):
        """mkapk's icon arguments: the game's icon (nx_extract saves the control data's English one as icon.jpg) as a 256 px PNG, and an adaptive icon's layers
        (Android 8+ launchers draw a plain icon small on a white disc): the icon fills the 72 dp the launcher's mask shows, over a blurred copy that fills
        the 108 dp layer. [] without Pillow or an icon"""
        src = next((d / 'icon.jpg' for d in (self.game, self.base) if (d / 'icon.jpg').exists()), None)
        if not src: say('[RED] no game icon in the extraction: the app gets the system\'s default icon'); return []
        try: from PIL import Image, ImageFilter
        except ImportError: say('[RED] no Pillow (pip install pillow): the app gets the system\'s default icon'); return []
        art = Image.open(src).convert('RGB'); plain, fg, bg = self.aw / 'icon.png', self.aw / 'icon_fg.png', self.aw / 'icon_bg.png'
        art.resize((256, 256), Image.LANCZOS).save(plain)
        layer, shown = 432, 288   # 108 dp and 72 dp at 4 px per dp
        art.resize((layer, layer), Image.LANCZOS).filter(ImageFilter.GaussianBlur(24)).save(bg)
        top = Image.new('RGBA', (layer, layer)); top.paste(art.resize((shown, shown), Image.LANCZOS), ((layer - shown) // 2,) * 2); top.save(fg)
        return ['--icon', plain, '--icon-fg', fg, '--icon-bg', bg]
    def icon_png(self):   # the game's icon as a plain 256 px PNG (the app's data pack carries it); None without Pillow or an icon
        self.icon()   # it writes the plain PNG (and the adaptive icon's layers)
        p = self.aw / 'icon.png'
        return p if p.is_file() else None

    # ---- data: the files the app reads from its files folder ----
    def drivers(self):
        """The Vulkan drivers the APK carries: --vulkan-driver first, then the folder's (the launcher's list)"""
        out = [self.o.vulkan_driver] if self.o.vulkan_driver else []
        if self.o.vulkan_driver_dir:
            out += sorted(f for f in self.o.vulkan_driver_dir.glob('*.so') if f.is_file())
        return out

    def loader_libs(self):
        """The hooks the data pack carries: the loader itself is the app's own (its native library folder, where the
        linker finds it by name), the hooks travel with the host (the host hands the loader the folder it sits in, and
        the loader looks the hooks up there). A pack with no driver of its own still carries them: the player may
        install one in the app and pick it for this game"""
        pre = [p for p in prebuilt_loader() if p.name in ADRENOTOOLS_HOOKS]
        if pre: return pre
        return [self.so.parent / n for n in ADRENOTOOLS_HOOKS]

    def loader_so(self):
        """The loader itself, for the per-game APK: its own lib folder is where the linker finds it by name"""
        pre = [p for p in prebuilt_loader() if p.name == ADRENOTOOLS_LOADER]
        if pre: return pre
        f = self.so.parent / ADRENOTOOLS_LOADER
        return [f] if f.is_file() else []

    def pack_dir_root(self):
        """The folder that will hold the data pack. The app may put it on the removable card (NX2A_PACK_DIR);
        without it the pack goes beside the build folders, on the phone's own storage"""
        given = os.environ.get('NX2A_PACK_DIR', '').strip()
        if given: return Path(given).expanduser()
        return self.work_root.parent / 'ports'

    def data_dir(self):
        """Where the data pack goes (and where the game reads its files from): the pack's own name in the output folder.
        The app's mode: the pack goes to the app's own folder, beside the build folders (the same volume, so the pack is written next to the build's files).
        The shared storage cannot hard link them (another volume), so a pack there is a copy of the whole game: minutes, and it breaks.
        The app may put the pack on the removable card (NX2A_PACK_DIR): there it is a copy all the same (Android's SELinux never lets an app link),
        and the build's own folder stays on the phone's storage, where the programs that run during the build can live"""
        stem = f'{app_stem(self.g, self.app_name)}-data'
        if self.o.app: return self.pack_dir_root() / stem
        return self.o.out / stem

    def data(self):
        PROG.stage('data pack', step='button-icon font')
        font_env, font_files = self.button_font()
        shared_env, shared_files = self.shared_font()
        font_env, font_files = font_env + shared_env, {**font_files, **shared_files}
        dst = self.data_dir()
        files = {**self.fps_files, **font_files}
        host = icon = None; drivers = []
        romfs, romfs_at = self.game / 'RomFS', None
        if self.o.app:   # the app runs the game itself: the pack carries what the APK used to (the host with the player's entry, the drivers, the icon, the gamepad's theme)
            # the app's mode: the pack points at the build folder's own RomFS instead of copying it (the same game twice on the phone is too much).
            # The pack's env.txt says where the host reads it from, and the manifest says the same, so the app knows the pack needs that folder.
            # A game without a RomFS (an OpenGL ES one) has none to point at: the pack keeps no RomFS, as before.
            if romfs.is_dir():
                romfs_at, romfs = str(romfs), None
            host = 'host/libdmplay.so'
            files[host] = self.so
            if theme := self.theme_files(): files.update(theme)
            if src := self.icon_png(): icon = 'icon.png'; files[icon] = src
            for d in self.drivers() + self.loader_libs(): files[f'drivers/{d.name}'] = d
            drivers = sorted({f'drivers/{d.name}' for d in self.drivers() + self.loader_libs()})
        man = datapack.build(dst, profile=self.ap, version=self.pkg_ver, display=self.display, patched=self.patched, romfs=romfs, romfs_at=romfs_at, shaders=self.dmsv,
                             aoc=self.aoc, sysdata=self.sysdata, language=self.lang, package=self.package, env=self.fps_env + font_env + (['DM_VK_MT_REPLAY=1'] if self.o.mt_replay else [])
                             + ([f'DM_VK_DRIVER={driver_lib_name(self.o.vulkan_driver)}'] if self.o.vulkan_driver else []), settings=TOUCH_SETTINGS if self.o.touch else None,
                             files=files, text=self.lists(), host=host, icon=icon, drivers=drivers, profile_h=getattr(self, 'profile_h', None),
                             pack_base=str(dst) if self.o.app else None,   # the app's mode: the pack lives in the app's own folder, not in the app's files folder
                             progress=lambda what: PROG.set(step=f'linking {what}'))
        self.data_path = dst; self.result['data'] = str(dst)
        PROG.end(f'{man["files"]:,} files, {man["bytes"] / 2**30:.1f} GB')

    def lists(self):
        """The launcher's lists: the languages the game supports (languages.txt) and the drivers the APK carries (drivers.txt).
        The device's own driver is the first choice: a driver in the folder is only used when the player picks it (the page)"""
        langs = []
        for code in self.langs:
            name = next((n for c, n in nacp.LANGUAGES if c == code), '')
            langs.append(f'{code}\t{name}')
        return {'languages.txt': '\n'.join(langs) + ('\n' if langs else ''),
                'drivers.txt': ''.join(f'{d.name}\n' for d in self.drivers())}

    def shared_font(self):
        """The font the game's shared font is: the player's own (--shared-font) or the one the tools ship
        (dm/tools/shared_cjk.ttf: the Latin base with the CJK glyphs merged in, the shape a game that parses the
        shared font expects, dm/tools/cjk_font.py builds it). The host has none of the console's own fonts, and
        the system's Latin one has no CJK glyphs, so a game in Chinese drew its menus as "?" (dm/host/hle_sdk16.c).
        The app's mode points at the shipped font where it lies (the payload), so the pack does not carry it and
        the path does not change when the pack moves; the desktop's mode puts it in the pack, which the install
        copies into the app's files folder on the device.
        Returns (env.txt rows, pack files); none without a font"""
        if self.o.shared_font:   # the player's own font: it goes in the pack, the install puts it where the host reads it
            dest = self.data_dir() if self.o.app else datapack.files_folder(self.package)
            return [f'DM_SHARED_FONT={dest}/standard.ttf'], {'standard.ttf': self.o.shared_font}
        if not SHARED_CJK.is_file():
            say('[RED] the shared font is missing (dm/tools/shared_cjk.ttf): the game\'s Chinese text will draw as "?"')
            return [], {}
        if self.o.app: return [f'DM_SHARED_FONT={SHARED_CJK}'], {}
        dest = datapack.files_folder(self.package)
        return [f'DM_SHARED_FONT={dest}/standard.ttf'], {'standard.ttf': SHARED_CJK}

    def button_font(self):
        """The stand-in for the system's Nintendo extension shared font: the controller-button icons in game text (U+E0E0 = A; Super Mario Bros. Wonder's menus showed '?' without it), which
        the host serves for shared-font type 5 (hle_sdk16.c, DM_SHARED_FONT_EXT). make_ext_font.py draws it, as the Mac and iOS builds do, made again when the tool or the letters change.
        Returns (env.txt rows, pack files); none without fontTools or a letter font, and the icons draw as '?'"""
        letters = next((Path(p) for p in LETTER_FONTS if Path(p).is_file()), None)
        try: import fontTools   # noqa: F401 (make_ext_font.py runs with this Python)
        except ImportError: letters, why = None, 'no fontTools (pip install fonttools)'
        else: why = 'no bold system font for the letters'
        if not letters: say(f'[RED] {why}: the button icons in the game\'s text draw as "?"'); return [], {}
        d = self.aw / 'font'; ttf, kf = d / 'nintendo_ext.ttf', d / 'nintendo_ext.key'
        key = nsp.fingerprint(('files', [DM / 'tools/make_ext_font.py', letters]))
        if not (ttf.is_file() and nsp.reused(kf, key) is not None):
            nsp.rmtree(kf); d.mkdir(parents=True, exist_ok=True)
            nsp.run([PY, DM / 'tools/make_ext_font.py', ttf, letters], self.logs / 'make_ext_font.log', env=self.env)
            kf.write_text(f'{key}\n')
        return [f'DM_SHARED_FONT_EXT={self.data_dir()}/nintendo_ext.ttf'], {'nintendo_ext.ttf': ttf}

    # ---- install (optional) ----
    def install(self):
        if self.o.app: return   # the app's mode: no APK, the app runs the game itself
        install.install(self.apk_path, self.data_path, self.tools['adb'], self.o.device or self.cfg.get('device'), replace_signer=self.o.replace_signer,
                        romfs_at=self.o.romfs_at, romfs_push=self.o.romfs_push)


def emit_result(r):   # the last JSON event of a port (the GUI's done screen); a summary line in the other modes
    if PROG.mode == 'json': PROG._json({'ev': 'result', **r})
