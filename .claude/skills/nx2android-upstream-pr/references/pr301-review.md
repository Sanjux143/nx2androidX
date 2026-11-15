# Worked example: the review of PR #301

The Chinese release of OCTOPATH TRAVELER 0 (`01001300232F4000`), shirai91/nx2android#301, reviewed 2026-11-13 in two rounds. The first round's three blockers and five inline notes, the second round's one blocker and six notes, and what answered each.

## What the pull request carried

- the profile of the Chinese release: `game.tsv`, `android.tsv`, `patches.py`, the shader tables (the same 10,324 shaders as the international release, in another order in the archives)
- two host rows for **that build** (`e3e401c2…`): `hle_uemalloc.c` (the FMallocBinned3 object at main+0x68052c8, its vtable at main+0x5b09f38) and `hle_fpslock.c` (t.MaxFPS at main+0x7c768d0, rhi.SyncInterval at main+0x7b35690) — the international release's build ID is a different one, so its behaviour does not change
- the AES provider fix (`uepak.py`: CommonCrypto is a Mac's alone, and the porter runs where the game is) with `tests/test_uepak.py`
- the tests for the Oodle decode's rounds (`tests/test_porter.py`)
- the gamepad theme pack `octopath0` and its `themes.tsv` row (it carried two; the international release's came out in the second round)

## The first round: three blockers

| The review said | The fix |
|---|---|
| "the README table loses Xenoblade Chronicles 3: it was the last cell (beside Viractal) and has no row now" | the table is re-flowed from the current main, not edited in place: every cell kept, the new one added next to the international release's, an odd count ending with a row of one game and an empty cell. The check is the **set** of games, not the diff (adding a cell re-pairs every row after it) |
| "two of the new UnrealShaders tests fail on Windows (release-windows.yml runs the test suite)" | the expected stamp was written with a slash (`Content/pakchunk0-Switch.pak\t3\n`), but `nsp.ue_stamp` prints `p.relative_to(d / 'RomFS')` — the platform's own path, a backslash on Windows. The tests now build the expected stamp with `nsp.ue_stamp(p.dirs['v1'])` and write the "another pak" one from it (the size changed, not the path) |
| "the profile registers `v1`, which is profile_guess's placeholder for an update file with no `[v<N>]` tag, not the update's real version" | the placeholder came from `version_of()`, which reads an English `[v<N>]`; the Chinese dump spells it `[升级档v1.0.5]`. The update is 1.0.5, so the real version is **v327680** (the international 1.0.5's): registered in `build`, `package`, `shader_versions`, the `android.tsv` `tested` row and the `patches.py` comment |

## The first round's inline notes

| The review said | The fix |
|---|---|
| "this row uses the international game's image. The port docs ask for a gameplay frame captured on the device for this port" | `docs/images/octopath-traveler-0-chinese.jpg`: a frame captured on the Xiaomi (the Chinese release in town, with the new gamepad on top), cropped to the game's picture and resized to the 1280x720 every image there uses |
| "porter/dumps.py also tries `Cryptodome.Cipher` (the name some system package managers give pycryptodome). The same second try here keeps the two consistent" | `uepak.py` tries `Crypto.Cipher` and then `Cryptodome.Cipher` |
| "on a machine with neither CommonCrypto nor pycryptodome this test errors instead of skipping … the expected value is the AES-256 decryption of the NIST plaintext block … not the NIST ECB-AES256 ciphertext" | the vector test skips without a provider; the comment above the key says what the value is (the NIST ciphertext for that block is f3eed1bd…) |
| "This row puts the new pack on the international release too … the maintainer will decide whether it goes in with this PR or on its own" | left as it is for that round, and the reply says so: dropping it is a one-line change. **He decided in the next round: it came out** (see below) |
| "please rebase onto the current main: it added Fire Emblem Engage and Pokémon Brilliant Diamond since, so the README table and the PORT_CHECKLIST count (now 46 / 103) conflict; the new checklist entry must also raise the total" | rebased onto the upstream's current main (by the time of the rebase the count was 51 / 106); the table re-flowed from it and the count became **52 / 107** — the total as well as the ported one |

## The second round: one blocker, six notes

The maintainer accepted the first round's fixes ("the README table keeps every game, the checklist reads 52 / 107, the stamp tests use nsp.ue_stamp, the profile registers v327680, and uepak.py also tries Cryptodome") and left one thing.

| The review said | The fix |
|---|---|
| "the themes.tsv row for the international release, which is another contributor's port (#195). Please drop that row here; it can come in its own PR" (the blocker) | the row is gone: the pack's row names only the Chinese release, and the international release keeps the ergonomic pack, so its APK does not change here. The reply says where the row will go: its own pull request, where #195's author can see it |
| "is v327680 the version in the update's CNMT, or does it come from the display version 1.0.5? The Asian release numbers its updates on its own. If the CNMT gives another number, a dump tagged [v<N>] the usual way does not match this row" | answered in the reply: the number is the update NCA's own (the one a dump's `[v<N>]` tag carries), not the display version the NACP holds; the Asian release numbers its updates as the international one does (its 1.0.2 is v131072, its 1.0.4 v262144, the international ones as well), so its 1.0.5 is v327680 — the build registered (main e3e401c2…) is that title's 1.0.5 update build (the patch title 01001300232F4800's). The profile now says where the number comes from, in a comment above the build row |
| "this repo has no phone app payload (files/tool/pylibs); that is nx2androidX's. Here the porter runs on a PC. Say 'pycryptodome elsewhere (requirements.txt; the Windows and Linux bundles ship it)' instead" | the `uepak.py` docstring says that now |
| "the same wording as in uepak.py. 'the phone app's own machine' is nx2androidX's. Here it is a Windows or Linux PC without CommonCrypto" | `tests/test_uepak.py`'s header and the comment on `test_without_commoncrypto` say "a Windows or Linux PC" |
| "oodle_job, oodle_where and 'upstream's pipeline.py' come from the fork. This repo's device_ue calls only device_oodle_job. Put the decoder body in device_oodle_job and remove oodle_job and oodle_where" | the fake port's decoder body is in `device_oodle_job`, the fork's `oodle_job` and `oodle_where` are gone, and the comment names porter/pipeline.py |
| "this mkdtemp folder is never removed, so each run leaves 5 folders in the temp directory. Add self.addCleanup(shutil.rmtree, root, True) after it" | added; the suite no longer piles folders up in the temp directory |
| "stickSize 160 here and menuBounds at line 221 are the phone artboard's values. Every other pack's tablet uses 184 and [-570, 570, -6, 710]" | the tablet artboard carries the tablet's values again; only the canvas preview changes, not the rendered atlas or the pack's theme.tsv |

The branch was rebased onto the current main once more (it had moved on): that rebase also brought the main's fix for the overlay harness's `_Atomic` declaration (#309), which the branch's own copy of that test needed — the suite failed to compile without it.

## What the reply said

One paragraph per point, what was changed and why it is right (the CNMT's version number, the platform's own path, the device's frame, the upstream's PC world). The row the maintainer took out was named as his call, with the pull request it will go to.

## The result

Four commits, each carrying one thing: the profile, the AES fix, the decode's tests, the theme. The fixes went into the commit they belong to (`git commit --fixup` + `git rebase -i --autosquash`), so the pull request reads as four changes rather than nine. The suite passes on the branch (316 tests, one skipped).
