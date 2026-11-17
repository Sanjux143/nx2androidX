"""Rebuild only a game's player runtime. The fresh components go into the app's private runtimes folder
(NX2A_RUNTIMES, one folder per title ID, with a build.json marker beside them) — or, when that is not
set, back into the pack's own places as before (the desktop tool and the upstream engine keep that
layout)."""
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

from porter import config, profiles_layer, toolchain
from porter.paths import DM
from porter.revision import PLAYER_API_REVISION

HOOKS = ('libhook_impl.so', 'libmain_hook.so', 'libfile_redirect_hook.so', 'libgsl_alloc_hook.so')

# Where the runtime lives in a pack: the host in its own folder, the managed hooks beside the drivers.
HOST = 'host/libdmplay.so'
HOOK_DIR = 'drivers'

# The files a runtime update owns and replaces in place.
RUNTIME_FILES = (HOST,) + tuple(f'{HOOK_DIR}/{name}' for name in HOOKS)


def install(source: Path, target: Path):
    """Put a staged component in its place. The rename is one atomic step: the file is never half there."""
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.replace(source, target)
    except OSError:
        # Another filesystem (a drivers folder on another volume): copy it in and rename it over.
        pending = target.with_name(target.name + '.part')
        with source.open('rb') as src, pending.open('wb') as dst:
            shutil.copyfileobj(src, dst)
            dst.flush()
            os.fsync(dst.fileno())
        os.replace(pending, target)


def runtimes_root():
    """The app's private runtimes folder (NX2A_RUNTIMES), or None when the caller did not give one:
    without it the runtime goes back into the pack, as the desktop tool's layout has it."""
    given = os.environ.get('NX2A_RUNTIMES', '').strip()
    return Path(given) if given else None


def build_marker(profile_h, no_lsfg):
    """The build fingerprint the runtime was made with: the player interface it speaks, the profile it was
    built from (the game_profile.h's hash — the alias headroom is in there), and the lsfg switch. The app
    compares this against what it expects before it loads the runtime."""
    return {'player_api_revision': PLAYER_API_REVISION, 'profile_h': profile_h or '', 'no_lsfg': bool(no_lsfg)}


def embed_profile(pack: Path, title_id: str, profile_dir: Path) -> bool:
    """Copy the profile a runtime was built from into the pack's own profile/<title ID>/ (the two tsv files,
    each one atomic rename; the source copy stays where it is - this is a copy, not a move). Best-effort: True
    when the pack carries them, False when they were already there or the write failed (an unmounted card, say)
    - the next rebuild simply falls back again."""
    target = pack / 'profile' / title_id
    if target == profile_dir: return False
    try:
        target.mkdir(parents=True, exist_ok=True)
        for src in sorted(profile_dir.iterdir()):
            if not src.is_file(): continue   # 子目录不属于档案本身
            pending = target / (src.name + '.part')
            with src.open('rb') as f, pending.open('wb') as dst:
                shutil.copyfileobj(f, dst)
                dst.flush()
                os.fsync(dst.fileno())
            os.replace(pending, target / src.name)
        return True
    except OSError:
        return False


def write_marker(target: Path, marker: dict):
    """The build marker goes in last: a runtime without its marker is rebuilt, never loaded half-known."""
    target.parent.mkdir(parents=True, exist_ok=True)
    pending = target.with_name(target.name + '.part')
    with pending.open('w', encoding='utf-8') as stream:
        json.dump(marker, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(pending, target)


class ProfileDrift(ValueError):
    """The pack was built with a profile that no longer exists; the player may choose the profile the game has now."""


def profile_candidates(pack, title_id):
    """Where the profile this pack was built with may live: the pack's own copy first (the two tsv files travel
    with the pack, so a rebuild on another device - or after the workspace is gone - finds them), then the build
    workspace's own copies (the headroom copy, the derived one, the provisional one - older packs rely on them
    until their first successful rebuild writes the pack's copy), then the player's imported profiles."""
    yield Path(pack) / 'profile' / title_id
    build = Path(os.environ.get('NX2A_WORK') or tempfile.gettempdir()) / f'{title_id}_build'
    for name in ('profile-mem', 'profile-derived', 'profile'):
        yield build / name / title_id
    yield profiles_layer.layer_dir() / title_id


def profile_fingerprint(directory):
    """sha256 of the game_profile.h the profile in this folder makes (as the host build makes it), or None when the
    folder holds no usable profile"""
    if not (directory / 'game.tsv').is_file() or not (directory / 'android.tsv').is_file(): return None
    with tempfile.TemporaryDirectory(prefix='nx2a_profile_') as t:
        header = Path(t) / 'game_profile.h'
        env = dict(os.environ, DM_GAMES_DIR=str(directory.parent), DM_GAMES_FIRST='1')
        run = subprocess.run([sys.executable, str(DM / 'games/game.py'), 'header', str(directory), str(header)],
                             capture_output=True, text=True, errors='replace', env=env)
        if run.returncode or not header.is_file(): return None
        return hashlib.sha256(header.read_bytes()).hexdigest()


def find_profile(pack, title_id, expected, allow_drift=False):
    """(the profile to rebuild with, its fingerprint, whether any candidate was there at all)

    A pack that recorded no fingerprint (made before the profiles were fingerprinted) cannot be checked: it gets
    the first profile there is, and the fingerprint of that one is recorded when the runtime is rebuilt. The player
    is told to port the game again if it misbehaves afterwards."""
    found = False
    first = None
    for candidate in profile_candidates(pack, title_id):
        fingerprint = profile_fingerprint(candidate)
        if fingerprint is None: continue
        found = True
        if first is None: first = (candidate, fingerprint)
        if expected is None or fingerprint == expected: return candidate, fingerprint, True
    if allow_drift and first is not None:
        # 没有一份对得上，但玩家让 App 用当前档案继续：这份就是这个游戏现在在用的档案
        # （完整重新移植用的也是它），重建出来的运行库与重移植是同一个东西。
        return first[0], first[1], True
    return None, None, found


def rebuild(pack, *, no_lsfg=False, jobs=None, allow_profile_drift=False, emit=print):
    pack = Path(pack).resolve()
    expected = os.environ.get('NX2A_PLAYER_API_REVISION')
    if expected is not None and expected != str(PLAYER_API_REVISION):
        raise ValueError('The runtime tools do not match this app. Refresh the environment and retry.')
    manifest_path = pack / 'nx2a-data.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    title_id = manifest.get('title_id', '')
    if not isinstance(title_id, str) or not re.fullmatch(r'[0-9A-Fa-f]{16}', title_id):
        raise ValueError('Invalid game identity. Port the game again from its original files.')
    fingerprint = manifest.get('profile_h')
    if not isinstance(fingerprint, str) or not fingerprint: fingerprint = None   # an old pack: trusted, not checked
    profile, chosen, found = find_profile(pack, title_id, fingerprint, allow_drift=allow_profile_drift)
    if profile is None:
        if not found:
            raise ValueError('No game profile for this game. Port the game again from its original files.')
        raise ProfileDrift('The game profile changed since this pack was built.')
    if fingerprint is not None and chosen != fingerprint:
        emit('The game profile changed since this pack was built. Rebuilding the runtime with the current profile.')
    ndk, _ = toolchain.find_ndk(config.load())
    if ndk is None:
        raise ValueError('The Android compiler is unavailable. Prepare the environment and retry.')
    root = Path(os.environ.get('NX2A_WORK') or tempfile.gettempdir()) / 'runtime'
    root.mkdir(parents=True, exist_ok=True)
    stage = None
    activated = False
    try:
        with tempfile.TemporaryDirectory(prefix=title_id + '-', dir=root) as scratch:
            scratch = Path(scratch)
            # Snapshot the two non-executable inputs; do not reuse generated headers or old objects.
            profile_root = scratch / 'profile'
            target = profile_root / title_id
            target.mkdir(parents=True)
            for name in ('game.tsv', 'android.tsv'):
                shutil.copyfile(profile / name, target / name)
            output = scratch / 'host'
            prebuilt = DM.parent.parent / 'tools' / 'adrenotools'
            use_prebuilt = all((prebuilt / name).is_file() for name in HOOKS)
            args = [sys.executable, str(DM / 'android/build.py'), '--game', title_id,
                    '--ndk', str(ndk), '--out', str(output), '--play', '--release',
                    '--jobs', str(jobs or os.cpu_count() or 8)]
            if no_lsfg: args.append('--no-lsfg')
            if not use_prebuilt: args.append('--adrenotools')
            env = dict(os.environ, DM_GAME=title_id, DM_GAMES_DIR=str(profile_root), DM_GAMES_FIRST='1')
            tc_lib = DM.parent.parent / 'toolchain' / 'lib'
            if tc_lib.is_dir(): env['LD_LIBRARY_PATH'] = str(tc_lib) + os.pathsep + env.get('LD_LIBRARY_PATH', '')
            emit('Building the player runtime')
            with subprocess.Popen(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace') as process:
                for line in process.stdout: emit(line.rstrip())
                if process.wait(): raise RuntimeError('Runtime compilation failed. Retry, or port the game again from its original files.')
            host = output / 'libdmplay.so'
            with host.open('rb') as stream: header = stream.read(20)
            if len(header) < 20 or header[:5] != b'\x7fELF\x02' or struct.unpack_from('<H', header, 18)[0] != 183:
                raise ValueError('The compiler did not produce an Android ARM64 runtime.')
            hooks = prebuilt if use_prebuilt else output
            for name in HOOKS:
                if not (hooks / name).is_file(): raise ValueError(f'Missing runtime component: {name}')
            stage = Path(tempfile.mkdtemp(prefix='.runtime-', dir=pack))
            for source in [host, *[hooks / name for name in HOOKS]]:
                with source.open('rb') as src, (stage / source.name).open('wb') as dst:
                    shutil.copyfileobj(src, dst)
                    dst.flush()
                    os.fsync(dst.fileno())
            target_root = runtimes_root()
            if target_root is not None:
                # The runtime goes to the app's private folder, one folder per title ID, with its build
                # marker beside it (the marker is written last: a runtime without one is rebuilt, never
                # loaded half-known). The pack keeps only its game data; the manifest's 'host' key goes
                # (a pack without it reads as the new layout — the runtime is the folder's, not the pack's).
                # The manifest's own management fields (the profile this was built from, the interface
                # revision) still come back, so the pairing check keeps working the way it always has.
                target = target_root / title_id.lower()
                for name in HOOKS:
                    install(stage / name, target / name)
                install(stage / 'libdmplay.so', target / 'libdmplay.so')
                write_marker(target / 'build.json', build_marker(chosen, no_lsfg))
                manifest.pop('host', None)
                manifest.pop('host_front', None)
                manifest['player_api_revision'] = PLAYER_API_REVISION
                manifest['profile_h'] = chosen
                embed_profile(pack, title_id, profile)
            else:
                # No runtimes folder given (the desktop tool, the upstream engine): the runtime lives in
                # the pack's own places, the way it always has. The hooks go in first and the host last
                # (the host is the one that loads them); each one is a single rename, so a file is never
                # half in place.
                for name in HOOKS:
                    install(stage / name, pack / HOOK_DIR / name)
                install(stage / 'libdmplay.so', pack / HOST)
                manifest['host'] = HOST
                manifest['player_api_revision'] = PLAYER_API_REVISION
                manifest['profile_h'] = chosen   # the profile this runtime was built from (a pack without one gets it now)
                manifest.pop('host_front', None)
                embed_profile(pack, title_id, profile)
            pending = stage / 'manifest.json'
            with pending.open('w', encoding='utf-8') as stream:
                json.dump(manifest, stream, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(pending, manifest_path)
            activated = True
            # The staged copy and the folders older packs kept their runtime in are dead now: only the
            # manifest (or, in the new layout, the runtimes folder's marker) activates a runtime.
            for old in pack.glob('.runtime-*'):
                if old.is_dir(): shutil.rmtree(old, ignore_errors=True)
                else: old.unlink(missing_ok=True)
        return {'data': str(pack), 'title_id': title_id, 'name': manifest.get('name', title_id)}
    finally:
        if stage is not None and not activated:
            shutil.rmtree(stage, ignore_errors=True)
