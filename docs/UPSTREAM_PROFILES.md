# Upstream game profiles

The game profiles that live in `nxnative/dm/games/` but are not ported to Android: upstream's, synced in bulk so a port can start from the profile upstream brought the game up with, instead of a survey.

A profile without an `android.tsv` is not ported. `porter build <folder> --experimental` copies the profile into the build folder, writes an experimental `android.tsv` and tries the build; the build may fail and the log says why ([ARCHITECTURE.md](ARCHITECTURE.md#game-profiles-on-android)). What a port adds on top is the game's `android.tsv` (status, the version and device it was tested on), the game's notes and a run on a real device ([PORT_CHECKLIST.md](PORT_CHECKLIST.md)).

## The sync

- **Source**: [seedds/nxnative](https://github.com/seedds/nxnative), `dm/games/<title ID>/`, commit `bf1e4edd48b448bbe3f8fadab6a14ff1819ac742` (2026-11-16)
- **Synced**: 89 directories — 87 games, plus the two sub-program directories of Super Mario 3D All-Stars (`010049900F546001`, `010049900F546002`)
- **Left out**: the 17 Switch 2 titles (`0400…`; the porter takes NSP / NSZ / XCI, not a Switch 2 game's `.nup`), the games that have an official Android release ([PORT_CHECKLIST.md](PORT_CHECKLIST.md)), and the profiles another Android port has but upstream has not merged yet (Persona 3 Portable, Pokémon: Let's Go, Pikachu!, Frostpunk, Skyrim, the two Assassin's Creed collections, Crash Bandicoot N. Sane Trilogy, Street Fighter 30th Anniversary Collection) — a later sync picks them up if upstream merges them
- **The sync changed nothing else**: the 63 ported games (54 playable, 2 wip, 2 boots, 5 experimental) keep their own profiles and their `android.tsv`

Redoing it later: fetch the same paths from upstream at a newer commit, copy the directories that are new or changed, and keep the notes of this file in step.

## The package the player imports

The app does not carry the profiles: they are half a gigabyte of shader tables, and every release would copy them again. The player imports a package of them instead, and the porter finds them in the player's own folder.

- **The package**: a zip of the profile folders, cut by the app's build (`packaging/android-apk/build_assets.py`, which calls `devtools/profiles_zip.py`; the revision and the upstream commit come from `porter/revision.py`). Every release carries it as a second asset (`nx2a-profiles-r<revision>-<commit>.zip`), and the Quark Drive folder holds it beside the APK.
- **The import**: the app's environment check has the button (the guided setup can be run again for it). The package is copied into the app's own folder - the guest process sees the app's files, not the phone's storage - and `porter profiles import <package>` unpacks it into the player's profile layer (`<NX2A_HOME>/profiles/`), after checking that every entry is a profile folder with a `game.tsv` and that nothing tries to leave the layer.
- **The layer**: outside the tool tree the app unpacks, so an app update never touches it: the player imports once and keeps it. The porter joins it to `DM_GAMES_DIR` (dm/games/game.py), so `game.py`, the shader tools and the host build all find the imported profiles.
- **Is there a newer package?**: the app carries the manifest of the package its build made (`profiles.json` in its assets: the name, the sha256 of the file, the revision), and the receipt of an import carries the digest of the package that was imported. The app compares the two digests and says so in the environment check - "not this build's package" - and nothing more: the revision is a label, not a rule, and a package that is not this build's is not wrong, it is another one.

On a desktop the checkout's own profiles come first and the layer fills the gaps; on the phone, where the app carries no profiles, the layer is the only place they are.

## What the sync needed from the tools

- `nxnative/dm/tools/nso_asm.py`: added upstream's `BL`, `CBNZW` and `MOVX` encoders, which the new profiles' patch files import (without them the rewriter cannot load the patch files at all, and it loads every game's)
- `nxnative/dm/tools/tests/test_patches.py`: knows the tables this fork's rewriter does not read yet (`BINDING`, `CAPABILITIES`, `STUBS`, `MATCH_CUTS`, `MATCH_FRAME_STEP`, `MATCH_STUBS`), so a profile that carries them is not a test failure

## Known gaps

Upstream's rewriter has a function-matched patch subsystem (`STUBS`, `MATCH_CUTS`, `MATCH_FRAME_STEP`, `MATCH_STUBS`, with `dm/tools/nso_match.py` and `dm/tools/engine_patches.py`) that this fork's snapshot does not have. For most profiles it does not matter: their registered builds use `CUTS`, `OBS` and `FRAME_STEP`, which this fork reads. For three games it does:

| Game | Title ID | What is missing |
|---|---|---|
| Ori and the Will of the Wisps | `01008DD013200000` | Its only patch is a `STUBS` entry (the lock on Unity's constant-buffer table), so it gets no patch at all here: expect it not to work |
| Xenoblade Chronicles X: Definitive Edition | `0100453019AA8000` | Its registered build's `CUTS` apply; the `MATCH_*` rules, which reach other builds, do not |
| Diablo II: Resurrected | `0100726014352000` | Its registered build's `CUTS` apply; its `MATCH_CUTS` rules do not |

`CAPABILITIES` (NieR:Automata, FINAL FANTASY RESONANCE's Asian release) is metadata about a build's verified features that upstream's launcher reads; this fork's Android host does not consume it, so it changes nothing here.

Also worth knowing: Super Mario 3D All-Stars builds, but only its launcher draws with NVN — Super Mario 64 and Super Mario Sunshine inside it call Vulkan, which the Android host does not replay, and Galaxy is 32-bit.

## Third-party notices

The profiles come from seedds/nxnative (MIT). One file carries another author's notice: `dm/games/01007300020FA000/patches.py` (ASTRAL CHAIN) holds MIT's notice and `Copyright (c) 2026 yogesh1239`, whose AstralChain-Dynamic-FPS the patch adapts; it is kept as it is.
