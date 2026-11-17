# The player's own game profiles: the profiles they imported (a package of nxnative/dm/games/<title ID>/ folders) into their own folder.
#
# The layer lives in the player's nx2android folder, not in the tool tree the app unpacks, so an app update never touches it: the player imports
# the package once and keeps it. game.py finds the profiles through DM_GAMES_DIR (dm/games/game.py "DM_GAMES_DIR"), so the layer joins that
# variable and every tool that reads a profile - the porter, the shader tools, the host build - sees the imported ones.
#
# The package's layout is checked before anything is written: an entry that is not a profile folder, or that tries to leave the layer, is
# refused, and every profile folder has to hold a game.tsv. The layer's receipt records what came in.
import hashlib, json, os, re, shutil, time, zipfile
from pathlib import Path

from porter.paths import user_dir
from porter.revision import PROFILES_COMMIT, PROFILES_REVISION

RECEIPT = 'manifest.json'          # what the import wrote: the package's revision and commit, and the profiles it holds
ID = re.compile(r'[0-9A-F]{16}')    # a profile folder's name: the base game's title ID


class LayerError(Exception):
    """The package cannot be imported"""


def layer_dir():
    """The player's profile layer: the folder the imported profiles live in"""
    return user_dir() / 'profiles'


def activate(env=None):
    """The layer joins DM_GAMES_DIR, so game.py finds the profiles there. Returns the variable's value.

    The layer is searched after the repository's own profiles (DM_GAMES_DIR's own order), so on a desktop the checkout wins and the layer
    fills the gaps; on the phone, where the app carries no profiles, the layer is the only place they are.
    """
    dirs = [d for d in os.environ.get('DM_GAMES_DIR', '').split(os.pathsep) if d]
    layer = str(layer_dir())
    if layer not in dirs:
        dirs.append(layer)
        os.environ['DM_GAMES_DIR'] = os.pathsep.join(dirs)
    if env is not None: env['DM_GAMES_DIR'] = os.environ['DM_GAMES_DIR']
    return os.environ['DM_GAMES_DIR']


def receipt():
    """What the last import wrote (None when the layer has none)"""
    path = layer_dir() / RECEIPT
    if not path.is_file(): return None
    try: return json.loads(path.read_text(encoding='utf-8'))
    except Exception: return None


def imported():
    """The title IDs the layer holds"""
    d = layer_dir()
    if not d.is_dir(): return []
    return sorted(p.name for p in d.iterdir() if p.is_dir() and ID.fullmatch(p.name))


def _entries(zf, source):
    """The package's profile files, as (zip info, parts) pairs; the parts are the path inside the layer.

    A package holds the profile folders at its root, or under dm/games/ (the repository's own layout). Anything else, and any entry that
    tries to leave the layer, is refused.
    """
    out = []
    for info in zf.infolist():
        if info.is_dir(): continue
        name = info.filename.replace('\\', '/')
        parts = [p for p in name.split('/') if p and p != '.']
        if parts[:2] == ['dm', 'games']: parts = parts[2:]
        if parts == [RECEIPT]: continue   # the package's own receipt, read below
        if not parts or '..' in parts or name.startswith('/') or not ID.fullmatch(parts[0]):
            raise LayerError(f'{source}: {info.filename!r} is not a game profile folder')
        out.append((info, parts))
    return out


def import_zip(path):
    """Imports a profiles package into the layer. Returns the receipt it wrote.

    The package's files are checked before any of them is written: every profile folder has to hold a game.tsv, so a package that is not a
    profiles package changes nothing. The receipt records the package's digest, so the app can tell whether the player imported the package
    this build made (the app carries the same digest in its own manifest).
    """
    source = Path(path).expanduser()
    if not source.is_file(): raise LayerError(f'{source} is not a file')
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    with zipfile.ZipFile(source) as zf:
        package = {}
        if RECEIPT in zf.namelist():
            try: package = json.loads(zf.read(RECEIPT).decode('utf-8'))
            except Exception: package = {}
        entries = _entries(zf, source.name)
        ids = sorted({parts[0] for _, parts in entries})
        if not ids: raise LayerError(f'{source.name}: the package holds no game profile')
        for i in ids:
            if not any(parts[0] == i and parts[-1] == 'game.tsv' for _, parts in entries):
                raise LayerError(f'{source.name}: the profile {i} has no game.tsv')
        layer = layer_dir(); layer.mkdir(parents=True, exist_ok=True)
        for info, parts in entries:
            dst = layer.joinpath(*parts)
            dst.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as f, open(dst, 'wb') as out: shutil.copyfileobj(f, out)
    rec = {'revision': package.get('revision', PROFILES_REVISION), 'commit': package.get('commit', PROFILES_COMMIT),
           'date': package.get('date', ''), 'imported_at': time.strftime('%Y-%m-%d'), 'count': len(ids), 'ids': ids,
           'package': source.name, 'sha256': digest}
    (layer / RECEIPT).write_text(json.dumps(rec, indent=2) + '\n', encoding='utf-8')
    return rec
