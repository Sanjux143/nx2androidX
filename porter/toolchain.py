# The tools a port needs on the player's machine, found and checked (`porter doctor`, the GUI's setup screen). Nothing here is shipped with the tool:
# the player installs the Android NDK (r27c), the .NET SDK (10) and, to install from the tool, platform-tools (adb), or has `porter tools --download` (porter.fetch)
# fetch them from Google and Microsoft into their nx2android folder (paths.player_tools()), which the finders here also search. The phone ships the .NET runtime
# instead of the SDK (it runs the two C# tools but never builds them), so a runtime is enough there.
import glob, importlib.util, os, re, shutil, subprocess, sys
from dataclasses import dataclass, field
from pathlib import Path

from porter import config
from porter.paths import LOCAL_TOOLS, NXNATIVE, player_tools

NDK_REVISION = '27.2.12479018'   # r27c: the NDK the Android host is built and tested with
NDK_RELEASE = 'r27c'
DOTNET_MAJOR = 10
PY_MODULES = {'lz4.block': 'lz4', 'capstone': 'capstone'}   # the rewriter (capstone) and the NSO reader (lz4)
EXE = '.exe' if os.name == 'nt' else ''
DISK_GB = 20   # the setup check's free space: a NEO-sized port (7 GB of build folder) with room to spare


class ToolError(Exception):
    pass


@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    required: bool = True
    path: str = ''
    fix: str = ''

    def as_dict(self): return dict(self.__dict__)


def _sdk_roots():   # Android SDK folders that may hold ndk/<version> and platform-tools
    out = [os.environ.get(k) for k in ('ANDROID_HOME', 'ANDROID_SDK_ROOT')]
    if os.name == 'nt': out.append(os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk'))
    elif sys.platform == 'darwin': out.append(os.path.expanduser('~/Library/Android/sdk'))
    else: out.append(os.path.expanduser('~/Android/Sdk'))
    return [Path(p) for p in out if p]


def ndk_revision(d):   # Pkg.Revision from an NDK folder's source.properties, None when it is not an NDK
    try: t = (Path(d) / 'source.properties').read_text(errors='replace')
    except OSError: return None
    m = re.search(r'Pkg\.Revision\s*=\s*([\d.]+)', t)
    return m.group(1) if m else None


def find_ndk(cfg=None):
    """(folder, revision) of the NDK to build with: the configured one, ANDROID_NDK_HOME / ANDROID_NDK_ROOT, then the usual install places; r27c is preferred when several are found"""
    cfg = cfg if cfg is not None else config.load()
    # NX2A_NDK: an NDK that ships with the program (the nixport package carries one, see packaging/nixport)
    given = [cfg.get('ndk'), os.environ.get('NX2A_NDK'), os.environ.get('ANDROID_NDK_HOME'), os.environ.get('ANDROID_NDK_ROOT')]
    for d in filter(None, given):   # an explicit choice is taken as it is, even when it is not r27c (the check says so)
        if ndk_revision(d): return Path(d), ndk_revision(d)
    found = [Path(p) for t in (LOCAL_TOOLS, player_tools()) for p in glob.glob(str(t / 'android-ndk-*'))] + [p for r in _sdk_roots() for p in sorted((r / 'ndk').glob('*'))] + [r / 'ndk-bundle' for r in _sdk_roots()]
    found = [(d, ndk_revision(d)) for d in found if ndk_revision(d)]
    if not found: return None, None
    return next((x for x in found if x[1] == NDK_REVISION), found[-1])


def find_dotnet(cfg=None):
    """The dotnet to use: the configured one as it is; otherwise the first of DOTNET_ROOT, PATH and the usual places that has SDK 10, else the first that has a
    runtime 10 (a runtime-only dotnet, as the phone ships it, runs the C# tools but cannot build them), else the first found"""
    cfg = cfg if cfg is not None else config.load()
    if cfg.get('dotnet') and Path(cfg['dotnet']).is_file(): return Path(cfg['dotnet'])
    progs = [os.environ.get(k) for k in ('ProgramFiles', 'ProgramW6432')] if os.name == 'nt' else []
    cands = [os.environ.get('DOTNET_ROOT') and str(Path(os.environ['DOTNET_ROOT']) / f'dotnet{EXE}'), shutil.which('dotnet'), str(LOCAL_TOOLS / 'dotnet' / f'dotnet{EXE}'), str(player_tools() / 'dotnet' / f'dotnet{EXE}'),
             str(Path.home() / '.dotnet' / f'dotnet{EXE}'), *[str(Path(p) / 'dotnet' / f'dotnet{EXE}') for p in progs if p], '/usr/local/share/dotnet/dotnet']
    found = list(dict.fromkeys(Path(c) for c in cands if c and Path(c).is_file()))
    major = lambda v: int(v.split('.')[0]) >= DOTNET_MAJOR
    return next((d for d in found if any(major(s) for s in dotnet_sdks(d))), next((d for d in found if any(major(v) for v in dotnet_runtimes(d))), found[0] if found else None))


def dotnet_sdks(dotnet):   # {'10.0.402': '/usr/local/share/dotnet/sdk', ...}
    try: r = subprocess.run([str(dotnet), '--list-sdks'], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired): return {}
    return dict(re.findall(r'^(\d+\.\d+\.\d+)\S* \[(.+)\]', r.stdout, re.M))


def dotnet_runtimes(dotnet):   # {'10.0.14': '/usr/local/share/dotnet/shared/Microsoft.NETCore.App', ...}
    """The installed Microsoft.NETCore.App runtimes: 'dotnet --list-runtimes' prints the framework, its version and the folder it is in. A runtime is all
    the phone's dotnet needs (it runs the two C# tools and never builds them)"""
    try: r = subprocess.run([str(dotnet), '--list-runtimes'], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired): return {}
    return dict(re.findall(r'^Microsoft\.NETCore\.App\s+(\d+\.\d+\.\d+)\S* \[(.+)\]', r.stdout, re.M))


def dotnet_root(dotnet):
    """The folder with dotnet's runtime, where an apphost (nx_extract) looks in DOTNET_ROOT: the SDK folder's parent, not the executable's, which on a Mac is
    a link or wrapper elsewhere (/usr/local/bin from Microsoft's installer, /opt/homebrew/bin). A runtime-only dotnet (the phone's) has no SDK folder: its root
    holds the runtime itself (<root>/shared/Microsoft.NETCore.App)"""
    sdks = dotnet_sdks(dotnet)
    if sdks: return Path(next(iter(sdks.values()))).parent
    runts = dotnet_runtimes(dotnet)
    if runts: return Path(next(iter(runts.values()))).parent.parent   # shared/Microsoft.NETCore.App → the root
    return Path(dotnet).resolve().parent


def prebuilt_cs_tools():
    """The two C# tools (the extractor and the shader translator) are built on the computer and shipped: the phone runs them and never builds them, so a
    .NET runtime is enough there. A computer that has to build them needs the SDK"""
    nx = os.environ.get('NX2A_NX_EXTRACT')
    xl = os.environ.get('NX2A_DMSH_XLATE') or str(NXNATIVE.parent / 'tools' / 'dmsh_xlate')
    exe = 'nx_extract.exe' if os.name == 'nt' else 'nx_extract'
    return bool(nx and (Path(nx) / exe).is_file() and (Path(xl) / 'dmsh_xlate.dll').is_file())


def find_adb(cfg=None):
    cfg = cfg if cfg is not None else config.load()
    for c in [cfg.get('adb'), shutil.which('adb'), *[str(r / 'platform-tools' / f'adb{EXE}') for r in _sdk_roots()], str(LOCAL_TOOLS / 'platform-tools' / f'adb{EXE}'), str(player_tools() / 'platform-tools' / f'adb{EXE}')]:
        if c and Path(c).is_file(): return Path(c)
    return None


def _adb_host_unsupported():
    """Google ships platform-tools for Linux as x86-64 only: an ARM64 Linux host (a phone) cannot run adb"""
    import platform
    return sys.platform.startswith('linux') and platform.machine().lower() in ('aarch64', 'arm64')


def _importable(m):
    try: return importlib.util.find_spec(m) is not None
    except (ImportError, ValueError): return False


def keys_check(path):
    """A prod.keys file's own state: the file is there and it holds the keys a dump needs. Only the names are read, never
    the values: a title.keys file renamed as prod.keys passes the file check and then fails every NCA header at the extract
    (the header key is derived from master_key_00, so a keyset without it opens nothing).
    → (ok, detail, fix)"""
    if not path: return False, 'not set', 'dump prod.keys from your own Switch and set its path'
    p = Path(path)
    if not p.is_file(): return False, f'no such file: {path}', 'dump prod.keys from your own Switch and set its path'
    names = set()
    try:
        with p.open('r', errors='replace') as f:
            for ln in f:
                if '=' in ln:
                    k = ln.split('=', 1)[0].strip()
                    if k: names.add(k)
    except OSError as e:
        return False, f'{path}: cannot be read ({e})', 'dump prod.keys from your own Switch and set its path'
    masters = sorted(n for n in names if n.startswith('master_key_'))
    if not masters:
        return False, f'{path}: no master keys in it (a title.keys file?)', 'dump prod.keys from your own Switch (the title keys are not enough)'
    if 'master_key_00' not in names and 'header_key' not in names:
        return False, f'{path}: no master_key_00 in it ({len(masters)} master keys)', 'dump prod.keys from your own Switch'
    return True, f'{path} ({len(names)} keys, {len(masters)} master keys)', ''


def checks(cfg=None, keys=None):
    """Every requirement, as Check rows (the order the setup screen shows them)"""
    cfg = cfg if cfg is not None else config.load()
    out = []
    v = sys.version_info
    out.append(Check('Python', v >= (3, 11), f'{v.major}.{v.minor}.{v.micro} ({sys.executable})', fix='Python 3.11 or later'))
    miss = [pip for m, pip in PY_MODULES.items() if not _importable(m)]
    if not (_importable('compression.zstd') or _importable('backports.zstd')): miss.append('backports.zstd')   # zstd: in the standard library from Python 3.14
    out.append(Check('Python modules', not miss, 'missing: ' + ', '.join(miss) if miss else 'lz4, capstone, zstd', fix='pip install ' + ' '.join(miss) if miss else ''))
    out.append(Check('Pillow', _importable('PIL'), 'the app icon from the game\'s icon' if _importable('PIL') else 'missing: the app gets no icon', required=False, fix='pip install pillow'))
    aes = _importable('Crypto.Cipher') or _importable('Cryptodome.Cipher')
    out.append(Check('pycryptodome', aes, 'NSZ / XCZ dumps (porter.dumps)' if aes else 'missing: NSZ / XCZ dumps must be decompressed to NSP / XCI first', required=False, fix='' if aes else 'pip install pycryptodome'))
    out.append(Check('fontTools', _importable('fontTools'), 'the button icons in game text' if _importable('fontTools') else 'missing: button icons in game text draw as "?"', required=False, fix='pip install fonttools'))
    ndk, rev = find_ndk(cfg)
    if not ndk: out.append(Check('Android NDK', False, 'not found', fix=f'Download missing tools, or install NDK {NDK_RELEASE} (developer.android.com/ndk/downloads) and set its folder'))
    else: out.append(Check('Android NDK', rev == NDK_REVISION, f'{rev} at {ndk}' + ('' if rev == NDK_REVISION else f': the host is built with {NDK_RELEASE} ({NDK_REVISION}); others are untested'),
                           required=rev.split('.')[0] == NDK_REVISION.split('.')[0], path=str(ndk), fix='' if rev == NDK_REVISION else f'install NDK {NDK_RELEASE}'))
    dn = find_dotnet(cfg)
    sdks = dotnet_sdks(dn) if dn else {}
    rt = dotnet_runtimes(dn) if dn else {}
    good = [s for s in sdks if int(s.split('.')[0]) >= DOTNET_MAJOR]
    rtgood = [v for v in rt if int(v.split('.')[0]) >= DOTNET_MAJOR]
    pre = prebuilt_cs_tools()   # the phone ships the C# tools: a runtime is enough there; a computer that builds them needs the SDK
    if good: detail = f'{", ".join(sdks)} ({dn})'
    elif rtgood: detail = f'runtime {", ".join(rtgood)} ({dn})'
    elif dn: detail = f'no .NET {DOTNET_MAJOR} ({dn})'
    else: detail = 'dotnet not found'
    ok = bool(good) or (bool(rtgood) and pre)
    if ok: fix = ''
    elif rtgood: fix = f'the C# tools are built from source here: install the .NET SDK {DOTNET_MAJOR} (dotnet.microsoft.com)'
    else: fix = f'Download missing tools, or install the .NET SDK {DOTNET_MAJOR} (dotnet.microsoft.com)'
    out.append(Check('.NET', ok, detail, path=str(dn or ''), fix=fix))
    adb = find_adb(cfg)
    if adb: out.append(Check('adb', True, str(adb), required=False, path=str(adb)))
    elif not _adb_host_unsupported():
        # an ARM64 Linux host (the phone app's own porter) cannot run adb at all, and there is nothing to fix:
        # Google ships platform-tools for Linux as x86-64 only. No row at all - the phone makes the APK and the
        # data pack itself, so a row about a tool it cannot use is only noise.
        out.append(Check('adb', False, 'not found: the tool cannot install on a device (the APK and data pack are still made)', required=False, path='',
                         fix='Download missing tools, or install Android platform-tools'))
    k = keys or cfg.get('keys')
    kok, kdetail, kfix = keys_check(k)
    out.append(Check('prod.keys', kok, kdetail, path=k or '', fix=kfix))
    where = Path(cfg.get('out') or Path.home())   # the output folder's drive, or the home folder's (a port's build folder goes beside the game files: the port checks that drive itself)
    where = next((p for p in (where, *where.parents) if p.exists()), Path.home())
    free = shutil.disk_usage(where).free / 2**30
    out.append(Check('Disk space', free >= DISK_GB, f'{free:.0f} GB free on {where.anchor}', required=False,
                     fix='' if free >= DISK_GB else f'a port takes about twice its game files on the game folder\'s drive (NEO: 7 GB); keep {DISK_GB} GB free'))
    return out


def require(cfg=None, keys=None, adb=False):
    """The checked tools for a port: {'ndk', 'dotnet', 'adb'}; ToolError naming every missing requirement"""
    rows = checks(cfg, keys)
    bad = [r for r in rows if not r.ok and (r.required or (adb and r.name == 'adb'))]
    if bad: raise ToolError('missing requirements: ' + '; '.join(f'{r.name}: {r.detail}' + (f' ({r.fix})' if r.fix else '') for r in bad))
    by = {r.name: r for r in rows}
    adb_row = by.get('adb')      # no row at all on a host that cannot run adb (the phone)
    return {'ndk': Path(by['Android NDK'].path), 'dotnet': Path(by['.NET'].path), 'adb': Path(adb_row.path) if adb_row and adb_row.ok else None}
