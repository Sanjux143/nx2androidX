"""Rebuild only a pack's player runtime. An atomic manifest switch activates the complete component set."""
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

from porter import config, toolchain
from porter.paths import DM
from porter.revision import PLAYER_API_REVISION

HOOKS = ('libhook_impl.so', 'libmain_hook.so', 'libfile_redirect_hook.so', 'libgsl_alloc_hook.so')


class ProfileDrift(ValueError):
    """The pack was built with a profile that no longer exists; the player may choose the profile the game has now."""


def profile_candidates(title_id):
    """Where the profile this pack was built with may live: the workspace's own copies first (the headroom copy,
    the derived one, the provisional one), then the payload's registered profile."""
    build = Path(os.environ.get('NX2A_WORK') or tempfile.gettempdir()) / f'{title_id}_build'
    for name in ('profile-mem', 'profile-derived', 'profile'):
        yield build / name / title_id
    yield DM / 'games' / title_id


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


def find_profile(title_id, expected, allow_drift=False):
    """(the profile to rebuild with, its fingerprint, whether any candidate was there at all)

    A pack that recorded no fingerprint (made before the profiles were fingerprinted) cannot be checked: it gets
    the first profile there is, and the fingerprint of that one is recorded when the runtime is rebuilt. The player
    is told to port the game again if it misbehaves afterwards."""
    found = False
    first = None
    for candidate in profile_candidates(title_id):
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
    profile, chosen, found = find_profile(title_id, fingerprint, allow_drift=allow_profile_drift)
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
            manifest['host'] = f'{stage.name}/libdmplay.so'
            manifest['player_api_revision'] = PLAYER_API_REVISION
            manifest['profile_h'] = chosen   # the profile this runtime was built from (a pack without one gets it now)
            manifest.pop('host_front', None)
            # The manifest is the commit point. Before it changes the old component set remains active.
            pending = stage / 'manifest.json'
            with pending.open('w', encoding='utf-8') as stream:
                json.dump(manifest, stream, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(pending, manifest_path)
            activated = True
        return {'data': str(pack), 'title_id': title_id, 'name': manifest.get('name', title_id)}
    finally:
        if stage is not None and not activated:
            # Cancellation can arrive just after replace() committed but before the assignment above.
            # Never remove a generation the manifest already names; an unreadable manifest is inconclusive.
            try:
                active = json.loads(manifest_path.read_text(encoding='utf-8')).get('host')
            except (OSError, ValueError):
                active = f'{stage.name}/libdmplay.so'
            if active != f'{stage.name}/libdmplay.so':
                shutil.rmtree(stage, ignore_errors=True)
