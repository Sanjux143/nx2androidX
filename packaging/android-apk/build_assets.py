#!/usr/bin/env python3
"""Assemble the phone app's payload: the rootfs, the toolchain, the tool's tree and the Python modules.

The app unpacks this on its first start. The rootfs comes from the nixport payload (the supply channel's release:
the patched glibc, Python and the rest of the runtime), the toolchain from Google's NDK and an ARM64 LLVM, and the
tool's own tree from this repository.

    python3 build_assets.py --out <dir> [--payload <file>] [--toolchain <dir>] [--wheels <dir>]

The result is what the Gradle build takes as the app's assets:

    <out>/rootfs.pkg      the rootfs (the payload's tarball, gzipped)
    <out>/tool/            the tool: porter/, nxnative/ and the Python modules
    <out>/toolchain/       the NDK, the ARM64 compiler, glslc
    <out>/drivers/         the Vulkan driver the app ships (Mesa's Turnip), unpacked by the app itself
    <out>/manifest.json    what is in there and what it is for
"""
import argparse, hashlib, importlib.util, json, os, re, shutil, subprocess, sys, tarfile, urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
# What the app needs from the repository: the pipeline and the engine. The desktop app's GUI (PySide6), the probes,
# the tests and the documentation are not on the phone.
KEEP = ['porter', 'nxnative']
SKIP_DIRS = {'__pycache__', '.git', '.github', '.githooks', '.claude', '.idea', '.vscode', 'probes', 'tests', 'devtools',
             'packaging', 'docs', 'gui', 'ios', 'out', 'build', '.pytest_cache'}
# The tool's own tree holds what a local build left behind (nx_extract's bin/ and obj/): the phone does not need it.
# The build tools have bin directories of their own (the toolchain's clang/bin, its bin/glslc): they are part of the
# tools, so only the tool's tree is stripped of them.
TOOL_SKIP = SKIP_DIRS | {'bin', 'obj'}
SKIP_FILES = {'.DS_Store'}
# The two fonts the shared font is built from (dm/tools/cjk_font.py): the phone never rebuilds it, so the built font
# and the license texts travel and the sources do not (4.25 MB of sources for 3.16 MB of built font).
SKIP_SOURCES = {'DroidSansFallbackFull.ttf', 'OpenSans-Regular.ttf'}
# The Python modules the porter needs on the phone (the desktop GUI's PySide6 is not among them).
MODULES = ['lz4', 'capstone', 'pillow', 'fonttools', 'pyyaml', 'pycryptodome', 'backports.zstd']
# The NuGet packages the C# tools need (nx_extract reads the NCAs): the phone restores them from its own folder.
NUGET = [('LibHac', '0.19.0')]
# A game profile folder (dm/games/<title ID>): the app does not carry the profiles (the player imports them)
PROFILE_DIR = re.compile(r'^[0-9A-F]{16}$')


def copy_tree(src: Path, dst: Path, skip=SKIP_DIRS):
    """Copies a tree, leaving out what the phone does not need"""
    for p in sorted(src.rglob('*')):
        rel = p.relative_to(src)
        if any(part in skip for part in rel.parts): continue
        if in_profile_dir(rel): continue   # the game profiles: the player imports them (porter profiles import), the app does not carry them
        if p.name in SKIP_FILES or p.name in SKIP_SOURCES or p.name.endswith(('.pyc', '.apk', '.dmsv', '.spv', '.pdb')): continue   # the debug symbols carry the build machine's paths
        target = dst / rel
        if p.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif p.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, target)
            if p.suffix == '.py': target.chmod(0o644)
        elif p.is_symlink():
            target.parent.mkdir(parents=True, exist_ok=True)
            link = os.readlink(p)
            if not target.exists(): target.symlink_to(link)


def in_profile_dir(rel: Path):
    """The path is inside a game profile folder (dm/games/<title ID>/): the profiles are not carried by the app.

    The player imports them instead (porter profiles import): the profile folders are half a gigabyte of shader tables, and the app's
    update would copy them again with every release. The tools that read a profile (game.py, the shader tools, the host build) find the
    imported ones through DM_GAMES_DIR.
    """
    parts = rel.parts
    if 'games' not in parts: return False
    return bool(PROFILE_DIR.match(parts[parts.index('games') + 1])) if parts.index('games') + 1 < len(parts) else False


def profiles_out(cache: Path):
    """Where the game profiles package goes: beside the assets, not in them"""
    d = cache / 'profiles'
    d.mkdir(parents=True, exist_ok=True)
    return d


def cut_profiles(games: Path, out: Path):
    """Cuts the game profiles package and returns (its manifest, the package's path).

    The package is the app's second artifact: the app carries only the manifest (the package's name, its digest
    and its revision), so the player can tell whether the package they imported is the one this build made.
    """
    spec = importlib.util.spec_from_file_location('profiles_zip', REPO / 'devtools/profiles_zip.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    manifest, path = mod.cut(games, out / f'nx2a-profiles-r{mod.PROFILES_REVISION}-{mod.PROFILES_COMMIT[:8]}.zip')
    mod.write_manifest(manifest, out / 'profiles.json')
    print(f'   {manifest["count"]} game profile(s), {manifest["size"] / 2**20:.1f} MB, sha256 {manifest["sha256"][:16]}')
    return manifest, path


def nuget_packages(target: Path, cache: Path = None):
    """The NuGet packages the tools need, as a local source: the phone has no network, and a restore that
    waits for a name server that never answers looks like a build that never ends.
    A copy in the cache is used when there is one: the download needs the network."""
    print('>> the NuGet packages (the phone builds offline)')
    packages = target / 'packages'
    packages.mkdir(parents=True, exist_ok=True)
    for name, version in NUGET:
        f = packages / f'{name.lower()}.{version}.nupkg'
        if not f.exists():
            local = (cache / f.name) if cache else None
            if local and local.exists():
                shutil.copy2(local, f)
            else:
                url = f'https://www.nuget.org/api/v2/package/{name}/{version}'
                with urllib.request.urlopen(url) as r, open(f, 'wb') as out:
                    shutil.copyfileobj(r, out, 1 << 20)
        print(f'   {f.name}')
    # the restore looks here and nowhere else: no name server, no timeout
    (target / 'NuGet.config').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<configuration>\n  <packageSources>\n    <clear />\n'
        '    <add key="local" value="packages" />\n  </packageSources>\n</configuration>\n', encoding='utf-8')
    return target


def wheels(target: Path, cache: Path = None):
    """The Python modules, as the aarch64 wheels for the rootfs's Python (3.13).
    A copy in the cache is used when there is one: the download needs the network."""
    if target.exists(): return target
    if cache and cache.exists():
        print(f'>> the Python modules (from {cache})')
        shutil.copytree(cache, target)
        return target
    print('>> the Python modules (aarch64 wheels for Python 3.13)')
    tmp = Path(str(target) + '.tmp')
    shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir(parents=True)
    names = []
    for m in MODULES:
        r = subprocess.run([sys.executable, '-m', 'pip', 'download', '--only-binary=:all:', '--no-deps',
                            '--platform', 'manylinux2014_aarch64', '--python-version', '3.13', '--implementation', 'cp',
                            '-d', str(tmp), m], capture_output=True, text=True)
        if r.returncode: raise SystemExit(f'pip download {m} failed:\n{r.stdout}\n{r.stderr}')
        names.append(m)
    for wheel in tmp.glob('*.whl'):
        import zipfile
        with zipfile.ZipFile(wheel) as z: z.extractall(target)
        print(f'   {wheel.name}')
    shutil.rmtree(tmp, ignore_errors=True)
    return target


# The programs outside the rootfs that the closure needs: the build tools and the C# tools. They are Linux
# binaries too, and the libraries they want are the rootfs's.
ROOTFS_EXT = ['toolchain/clang/bin/clang', 'toolchain/clang/bin/ld.lld', 'toolchain/clang/bin/llvm-strip',
              'toolchain/bin/glslc', 'tool/nxnative/dm/gfx/tools/dmsh_xlate_bin/dmsh_xlate',
              'tool/tools/nx_extract/nx_extract', 'tool/tools/dmsh_xlate/dmsh_xlate']


def payload_rootfs(payload: Path, out: Path, assets: Path):
    """The rootfs out of the payload: the release gives a tarball with the rootfs inside.

    The rootfs is a whole Debian userland (a gigabyte unpacked) and the porter runs a small part of it: the
    software renderer, the video codecs, the X11 stack and the locales are never loaded. It is trimmed to the
    closure of the programs that are started (rootfs_trim.py) before it goes into the app."""
    print(f'>> the rootfs out of {payload.name}')
    # the name matters: the build expands an asset called *.tar.gz, and the app wants the stream as it is
    target = out / 'rootfs.pkg'
    if target.exists(): return target
    trimmed = out / 'rootfs-trim.tar'
    cmd = [sys.executable, str(Path(__file__).resolve().parent / 'rootfs_trim.py'),
           '--rootfs', str(payload), '--out', str(trimmed), '--ext-dir', str(assets),
           '--ext-search', 'toolchain/lib']
    for e in ROOTFS_EXT:
        cmd += ['--ext', e]
    subprocess.run(cmd, check=True)
    import gzip
    with trimmed.open('rb') as src, gzip.open(target, 'wb', compresslevel=6) as f:
        shutil.copyfileobj(src, f, 1 << 20)
    trimmed.unlink()
    return target


DOTNET_MAJOR = 10
DOTNET_INDEX = f'https://builds.dotnet.microsoft.com/dotnet/release-metadata/{DOTNET_MAJOR}.0/releases.json'


# The Vulkan driver the app ships, so that a player has one without a network: Mesa's Turnip for the Adreno 8xx,
# from the release v39 of StevenMXZ's Adreno-Tools-Drivers. An archive and not the library: the library is 8.4 MB and
# the APK does not compress a .so, while the archive is 2.5 MB (a zip of a zip does not shrink either, but it is a
# quarter of the size). The app unpacks it into its own folder when it starts.
DRIVER_NAME = 'Turnip_Gen8_V39.zip'
DRIVER_URL = f'https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v39/{DRIVER_NAME}'
DRIVER_SHA256 = '572e415c11b636ea2a4c4968c60a9b24e459302549c8ea38e814dbd07e40654d'


def driver_archive(cache: Path, out: Path):
    """The Vulkan driver the app ships, fetched once and kept in the cache (the download needs the network).
    The checksum is pinned: the archive is a binary the app hands to the phone's graphics driver."""
    target = out / DRIVER_NAME
    local = cache / DRIVER_NAME
    if not (local.exists() and hashlib.sha256(local.read_bytes()).hexdigest() == DRIVER_SHA256):
        print(f'>> the Vulkan driver ({DRIVER_NAME})')
        req = urllib.request.Request(DRIVER_URL, headers={'User-Agent': 'nx2android-android-apk'})
        part = local.with_suffix('.part')
        with urllib.request.urlopen(req, timeout=120) as r, open(part, 'wb') as f:
            shutil.copyfileobj(r, f, 1 << 20)
        part.replace(local)
    got = hashlib.sha256(local.read_bytes()).hexdigest()
    if got != DRIVER_SHA256:
        local.unlink(missing_ok=True)
        raise SystemExit(f'android-apk: the driver archive is not the one we pinned ({got})')
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(local, target)
    print(f'   {DRIVER_NAME} ({target.stat().st_size / 2**20:.1f} MB)')
    return target


def dotnet_runtime(cache):
    """The .NET runtime for an ARM64 Linux host: the newest one of the major the port builds with, checked against the
    SHA-512 in Microsoft's release list. The runtime, not the SDK: the phone only runs the two C# tools (nx_extract and
    the shader translator), which are built on the computer and shipped with the app — the phone cannot build them (its
    SDK crashes without ICU and a restore needs the network), so the SDK's compilers and MSBuild would be dead weight
    there (221 MB against the runtime's 50)"""
    req = urllib.request.Request(DOTNET_INDEX, headers={'User-Agent': 'nx2android-android-apk'})
    with urllib.request.urlopen(req, timeout=120) as r: rel = json.loads(r.read())
    item = None
    for release in rel['releases']:   # newest first
        runtime = release.get('runtime')   # the runtime's own files (the SDK's list holds the SDK's tarball)
        for f in (runtime or {}).get('files', []):
            if f.get('rid') == 'linux-arm64' and f['name'] == 'dotnet-runtime-linux-arm64.tar.gz':
                item = {'url': f['url'], 'hash': f['hash'], 'version': runtime['version']}
                break
        if item: break
    if not item: raise SystemExit(f'no .NET runtime {DOTNET_MAJOR} for linux-arm64 in {DOTNET_INDEX}')
    target = cache / Path(item['url']).name
    if not target.exists():
        print(f'>> the .NET runtime ({item["version"]})')
        req = urllib.request.Request(item['url'], headers={'User-Agent': 'nx2android-android-apk'})
        with urllib.request.urlopen(req, timeout=120) as r, open(target.with_suffix('.part'), 'wb') as f:
            shutil.copyfileobj(r, f, 1 << 20)
        part = target.with_suffix('.part')
        got = hashlib.sha512(part.read_bytes()).hexdigest()
        if got.lower() != item['hash'].lower():
            part.unlink(missing_ok=True)
            raise SystemExit("the .NET runtime's SHA-512 is not the one Microsoft's list gives")
        part.replace(target)
    item['file'] = target
    return item


def finalize_metadata(out):
    # The APK's assets have no permissions, so the modes of the tool's tree travel in a file of their own: the
    # guest's programs have to be executable, or the porter cannot start them.
    modes = {}
    for tree in ('tool', 'toolchain'):
        root = out / tree
        if not root.exists(): continue
        for p in root.rglob('*'):
            if p.is_file():
                modes[f'{tree}/{p.relative_to(root)}'] = p.stat().st_mode & 0o777
    (out / 'modes.json').write_text(json.dumps(modes))
    print(f'>> the modes of {len(modes)} file(s)')

    # the build's stamp: the app compares it with the one the tree was unpacked with, and copies the tree again
    # when it changed (the tree is big: the games' profiles alone are a third of it)
    stamp = hashlib.sha256()
    for tree in ('tool', 'toolchain'):
        root = out / tree
        if not root.exists(): continue
        for p in sorted(root.rglob('*')):
            if p.is_file():
                stamp.update(f'{tree}/{p.relative_to(root)}:{p.stat().st_size}\n'.encode())
                with p.open('rb') as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        stamp.update(chunk)
    (out / 'build-id').write_text(stamp.hexdigest() + '\n')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--finalize', action='store_true', help='refresh content stamps after all runtime tools have been staged')
    ap.add_argument('--payload', default='')
    ap.add_argument('--toolchain', default='')
    ap.add_argument('--cache', default='')
    ap.add_argument('--no-wheels', action='store_true')
    ap.add_argument('--no-dotnet', action='store_true', help='do not ship the .NET SDK: the app downloads it on the first run')
    a = ap.parse_args()
    out = Path(a.out); cache = Path(a.cache or (Path(__file__).resolve().parent / '.cache'))
    if a.finalize:
        finalize_metadata(out)
        return 0
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)

    payload = Path(a.payload) if a.payload else cache / 'payload-arm64.tar.gz'
    if not payload.exists(): raise SystemExit(f'{payload} is missing (fetch it from the nixport payload release)')

    tool = out / 'tool'
    (tool / 'tools').mkdir(parents=True, exist_ok=True)
    for name in KEEP:
        src = REPO / name
        if not src.exists(): continue
        copy_tree(src, tool / name, skip=TOOL_SKIP)
    # the tool's own patch script (the interpreter paths of the programs the porter starts) and the SDK's
    # globalization fix (the phone's .NET has no ICU): both are run by the app, so they travel with it
    shutil.copy2(REPO / 'packaging/android-apk/patch_interp.py', tool / 'tools/patch_interp.py')
    shutil.copy2(REPO / 'packaging/android-apk/fix_globalization.py', tool / 'tools/fix_globalization.py')
    # The license texts the app redistributes: its own (the tree's LICENSE carries the GPL-3.0-or-later text, and
    # NOTICE.md says what it covers and who holds the copyright), the index of the third-party components, and the
    # notice of the app's own license. The third-party licenses of the vendored code travel with it, in their own
    # directories.
    for src, dst in (('LICENSE', 'LICENSE'), ('NOTICE.md', 'NOTICE.md'), ('THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md'), ('android/NOTICE.md', 'android/NOTICE.md')):
        d = tool / dst; d.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(REPO / src, d)
    # the C# tools are built on the phone: the NuGet packages they restore have to be there (no network)
    nuget_packages(tool / 'nxnative' / 'dm' / 'tools' / 'nx_extract', cache / 'nuget')
    # nx_extract itself: built here for linux-arm64, because the phone's .NET SDK cannot build it (its
    # globalization mode stops the SDK, and a restore needs the network)
    nx = tool / 'tools' / 'nx_extract'
    if not (nx / 'nx_extract').exists():
        print('>> nx_extract (linux-arm64): the .NET SDK is missing, the phone cannot build the tool')
    else:
        print(f'>> nx_extract (linux-arm64) at {nx}')
    if not a.no_wheels:
        wheels(tool / 'pylibs', cache / 'pylibs')

    toolchain = Path(a.toolchain) if a.toolchain else cache / 'toolchain'
    if not toolchain.exists(): raise SystemExit(f'{toolchain} is missing (run build_toolchain.py first)')
    print('>> the build tools')
    copy_tree(toolchain, out / 'toolchain')

    # the rootfs is trimmed against the tool tree and the build tools: they have to be there first
    payload_rootfs(payload, out, out)

    # The .NET runtime: the extractor and the shader translator are C# and run on it. They are built on the computer
    # and shipped with the app (the phone cannot build them), so the phone only needs a runtime. Shipping it makes
    # the first run work offline; downloading it (the porter's own path) keeps the APK small.
    dotnet = None
    if not a.no_dotnet:
        dotnet = dotnet_runtime(cache)
        shutil.copy2(dotnet['file'], out / 'dotnet.pkg')

    # The Vulkan driver the app ships: a player has one without a network. The app unpacks it into its own folder
    # when it starts (the archive travels; the library does not).
    driver_archive(cache, out / 'drivers')

    # The game profiles: the app does not carry them (half a gigabyte of shader tables), the player imports a
    # package into their own folder. The package is cut here, beside the app that does not carry it, and the
    # app carries only its manifest: the package's name, its digest and its revision. The player compares that
    # with the receipt of what they imported, so the app can say whether there is a newer package - no version
    # rule, no nagging.
    print('>> the game profiles package')
    profiles_manifest, profiles_zip = cut_profiles(REPO / 'nxnative/dm/games', profiles_out(cache))
    (out / 'profiles.json').write_text(json.dumps(profiles_manifest, indent=2) + '\n')

    finalize_metadata(out)

    manifest = {'schema': 'nx2a.phone-assets.v1',
                'rootfs': 'rootfs.pkg',
                'tool': 'tool',
                'toolchain': 'toolchain',
                'modules': MODULES if not a.no_wheels else [],
                'dotnet': 'dotnet.pkg' if dotnet else '',
                'dotnet_sha512': dotnet['hash'] if dotnet else '',
                'drivers': 'drivers'}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    size = sum(f.stat().st_size for f in out.rglob('*') if f.is_file())
    print(f'>> the payload at {out}: {size / 2**20:.0f} MB')
    return 0


if __name__ == '__main__':
    sys.exit(main())
