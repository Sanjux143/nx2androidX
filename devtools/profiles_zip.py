#!/usr/bin/env python3
"""Cut a package of the game profiles: the player imports it into their own folder (porter profiles import).

The app does not carry the profiles: they are half a gigabyte of shader tables, and every release would copy them again. The
package holds the profile folders of nxnative/dm/games (<title ID>/) and a manifest that says what it is.

    python3 devtools/profiles_zip.py [--out <file>] [--games <folder>]

The revision and the upstream commit come from porter/revision.py: the revision counts the syncs of upstream's profiles, the
commit is the upstream commit the profiles were taken from (docs/UPSTREAM_PROFILES.md).
"""
import argparse, hashlib, json, re, shutil, sys, time, zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO))
from porter.revision import PROFILES_COMMIT, PROFILES_REVISION   # noqa: E402

ID = re.compile(r'^[0-9A-F]{16}$')


def cut(games: Path, out: Path):
    """Writes the package: the profile folders, and the manifest that says what it is.

    The package is reproducible: the same profiles give the same bytes, so the digest of the file is the digest of
    its content - the app compares it with the digest of the package the player imported, and a rebuild that changed
    nothing must not look like a new package. The entries carry a fixed timestamp and a fixed mode, and the manifest
    inside says what the profiles are (the revision and the upstream commit), not when the package was cut.

    The manifest that goes beside the package (what the app carries) has the cut's date as well.
    """
    ids = sorted(d.name for d in games.iterdir() if d.is_dir() and ID.match(d.name))
    if not ids: raise SystemExit(f'{games} holds no game profile')
    manifest = {'revision': PROFILES_REVISION, 'commit': PROFILES_COMMIT, 'count': len(ids), 'ids': ids}
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        _write(zf, 'manifest.json', json.dumps(manifest, indent=2) + '\n')
        for i in ids:
            for p in sorted((games / i).rglob('*')):
                if p.is_file() and '__pycache__' not in p.parts:
                    _add(zf, f'{i}/{p.relative_to(games / i).as_posix()}', p)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    return dict(manifest, date=time.strftime('%Y-%m-%d'), name=out.name, sha256=digest, size=out.stat().st_size), out


def _entry(name: str):
    """One entry of the package: a fixed timestamp and a fixed mode, so the package is reproducible"""
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    return info


def _write(zf: zipfile.ZipFile, name: str, data: bytes):
    zf.writestr(_entry(name), data)


def _add(zf: zipfile.ZipFile, name: str, path: Path):
    """One file of the package, read as it goes (a shader table is a hundred megabytes)"""
    with zf.open(_entry(name), 'w') as dst, open(path, 'rb') as src:
        shutil.copyfileobj(src, dst)


def write_manifest(manifest: dict, path: Path):
    """The manifest beside the package: what the app carries, so it can tell whether the player's package is this one"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--out', default='', help='the package to write (default: <out dir>/nx2a-profiles-r<revision>-<commit>.zip)')
    ap.add_argument('--games', default=str(REPO / 'nxnative/dm/games'), help='the profiles folder')
    ap.add_argument('--out-dir', default=str(REPO / 'dist'), help='where the package goes when --out is not given')
    a = ap.parse_args()
    games = Path(a.games)
    out = Path(a.out) if a.out else Path(a.out_dir) / f'nx2a-profiles-r{PROFILES_REVISION}-{PROFILES_COMMIT[:8]}.zip'
    manifest, _ = cut(games, out)
    write_manifest(manifest, out.with_suffix('.json'))
    print(f'{out}\n{manifest["count"]} game profile(s), r{manifest["revision"]} ({manifest["commit"][:8]}), {manifest["size"] / 2**20:.1f} MB, sha256 {manifest["sha256"][:16]}')


if __name__ == '__main__':
    main()
