---
name: nx2android-upstream-pr
description: How to contribute a port to the upstream engine repository (shirai91/nx2android): the branch and the GitHub fork a pull request needs, what a port's pull request carries (the profile, the host rows for that build, the tool fixes and their tests, the games table row, the device image, the checklist line), the review's recurring demands, and the commands that open, update and answer it. Use whenever the task is to push a port upstream, open or update a pull request against shirai91/nx2android, rebase a pull request onto the upstream main, answer the maintainer's review, or decide what a contribution to upstream has to carry. Bringing a game up on the phone is the nx2android-dev skill's; cutting a release is nx2android-release's.
---

# nx2android: contributing a port upstream

Upstream is **shirai91/nx2android**: the engine repository, where the game profiles and the shared core live and where the games table is kept. This checkout (zFitness/nx2androidX) is the product line: the phone app, the release line, and its own `main` (the upstream mirror, fast-forward only), `android` (product and release) and `next` (integration). A port reaches upstream as a **pull request**; this is that procedure, and the traps that come with it.

The port itself (the profile, the phone, the bring-up) is the **nx2android-dev** skill's ([.claude/skills/nx2android-dev](../nx2android-dev/SKILL.md)); a release is **nx2android-release**'s ([.claude/skills/nx2android-release](../nx2android-release/SKILL.md)). What is here is the part that leaves this checkout.

## The pull request needs a fork of upstream

GitHub wants a fork relation: `zFitness/nx2androidX` is **not** a fork of the upstream (it is the product's own repository), so a pull request against `shirai91/nx2android` needs one:

```bash
gh repo fork shirai91/nx2android --clone=false        # zFitness/nx2android
git remote add fork git@github.com:zFitness/nx2android.git
```

Every `gh` command about the pull request names the repository it acts on: `--repo shirai91/nx2android` for the pull request, `--repo zFitness/nx2android` for the branch's own repository.

## The branch

A contribution is a branch off the **current upstream main**, in a worktree (the main checkout keeps the product line):

```bash
git fetch upstream main                                  # the truth; the local mirror and the fork's main go stale
git worktree add ~/.local/share/opencode/worktree/<slug> -b feat/<slug> upstream/main
```

The local `main` mirror and `zFitness/nx2android`'s own `main` both lag the upstream (the mirror is synced on its own schedule, the hub change's "upstream sync" line). Always `git fetch upstream main` before branching or rebasing, and compare with `git rev-list --left-right --count HEAD...upstream/main`.

## What a port's pull request carries

| Part | Where | Note |
|---|---|---|
| The profile | `nxnative/dm/games/<title ID>/` | `game.tsv`, `android.tsv`, `patches.py`, the shader tables |
| The host rows **for that build** | `nxnative/dm/host/hle_*.c` | per-build tables keyed by the NSO build ID: a regional or other release is **its own binary**, so it needs its own rows (the allocator's object and vtable, the console variables) |
| The tool fixes the port needed | `nxnative/dm/gfx/tools/`, `porter/` | with their tests (`tests/test_*.py`) |
| The games table row | `README.md` | with the device's gameplay frame in `docs/images/<slug>.jpg` (1280x720) |
| The checklist line | `docs/PORT_CHECKLIST.md` | the game ticked, and the `N / M ported` count **with the total raised too** |

A pull request may carry more than the profile — the tool fix the port needed is part of it (the AES provider that only a Mac had, for instance, is what made the phone able to decode an encrypted pak at all).

## The review's recurring demands

The maintainer reads the pull request against the upstream's own rules, and the same points come up every time. The worked example (the Chinese release of OCTOPATH TRAVELER 0, PR #301) is in [references/pr301-review.md](references/pr301-review.md).

- **The games table is re-flowed from the current main.** Every game is kept — the diff is large even for one cell, because adding a cell re-pairs the rows after it, so check the **set** of games and not the diff. An odd number of cells ends with a row of one game and an empty cell (`| ![game](…) | |`).
- **The version is the real one.** `profile_guess` writes `v1` when it cannot read the update's version tag (it looks for an English `[v<N>]` in the file name; a Chinese dump spells it `[升级档v1.0.5]`). Register the update's **real** version: the number is the update NCA's own — the one a dump's `[v<N>]` tag carries — and not the display version the NACP holds (the build row's second column). A regional release numbers its updates **on its own**, so take the number from that release's own update series: the public update listings name every update's tag (the international OCTOPATH TRAVELER 0's 1.0.2/1.0.3/1.0.4/1.0.5 are v131072/v196608/v262144/v327680) and tinfoil.io lists the build IDs of a title and of its patch title per version — so register that release's own update build (the patch title 0100…800's), not the international one's. The number belongs in `build`, `package`, `shader_versions`, the profile's `android.tsv` `tested` row and the `patches.py` comment; say in a comment where it comes from, so the next reader does not have to ask.
- **A row for another game's port is that port's own pull request.** The gamepad theme table (`nxnative/dm/android/themes/themes.tsv`) gives every title ID a pack, and a row changes that game's APK: a row for a game you did not port cannot ride along in your pull request. The maintainer took the row for the international release (another contributor's port, #195) out of PR #301 and asked for it in its own pull request, where that port's author can see it. Your pull request adds rows for the titles it ports, and no others.
- **The upstream is a PC repository; the fork's world does not belong in it.** The phone app's payload (`files/tool/pylibs`), "the phone app's own machine" and the fork's pipeline names (`oodle_job`, `oodle_where`) are this fork's: there the porter runs on a Windows or Linux PC (pycryptodome is in `requirements.txt` and in those bundles) and the pipeline calls `device_oodle_job`. Write the upstream's world into the upstream's files — a fake port in a test implements the upstream's own names, and a comment says "a Windows or Linux PC".
- **The tests run on Windows too.** The CI runs the suite on three platforms: never write a path separator into an expected value (build it with the platform's own, e.g. `nsp.ue_stamp(dir)`), and a test that needs an optional package skips when it is missing instead of erroring.
- **The image is the device's.** A gameplay frame captured on the device, in `docs/images/`, not the publisher's artwork and not a frame from another release.
- **Rebase onto the current main** before asking for a merge, and take the counts in the checklist from that main (the games it added are counted there too).
- **The review comes in rounds.** The first round of PR #301 had three blockers and five notes; the second (after the fixes and a rebase) one blocker and six notes the maintainer called small and non-blocking. Answer every note anyway — they are the next round's blockers — one paragraph per point, and say what each fix was.

## Opening and updating it

```bash
git push fork feat/<slug>                                # the branch goes to the fork
gh pr create --repo shirai91/nx2android --base main --head zFitness:feat/<slug> \
    --title "feat(profile): <game> plays on android" --body-file <body>

gh pr view <n> --repo shirai91/nx2android --json reviews,comments,mergeable
gh api repos/shirai91/nx2android/pulls/<n>/reviews --jq '.[] | "=== " + (.id|tostring) + " " + .state + "\n" + .body'
gh api repos/shirai91/nx2android/pulls/<n>/comments --jq '.[] | "=== " + .path + ":" + (.line|tostring) + "\n" + .body'
```

After a rebase the branch is rewritten: `git push --force-with-lease fork feat/<slug>`. The pull request follows the branch; its commits are replaced in place.

Answer the review as a comment on the pull request, one paragraph per point, and say what each fix was. A point the maintainer leaves to himself (a row that touches another game's port, for instance) is **not** yours to keep: he takes it out and asks for it in its own pull request, where that port's author can see it — so drop it and say where it will go.

## The traps

- **A stale mirror makes a wrong "rebase"**: the fork's `main` and the local `main` are both behind the upstream; `git fetch upstream main` is the only truth.
- **The main moves while you wait**: fetch it again before every push. The rebase can bring a fix your branch's copy of a test needed (the overlay harness's `_Atomic` declaration, #309), and the whole suite has to pass on the branch — a test that only compiles on your machine breaks the pull request.
- **The games table's diff lies**: one added cell rewrites the rows after it. Compare the set of game names, not the diff.
- **`v1` in a profile is a placeholder**, not a version: a player's dump named the usual way then lands on a derived profile (a red line and a bigger address region).
- **A regional release is its own build**: its own main build ID, its own host rows, its own title ID, its own games table cell, its own image — and its own checklist line.
- **A theme pack has two artboards**: the phone one and the tablet one. The tablet carries the tablet's values (`stickSize 184`, `menuBounds [-570, 570, -6, 710]`), the phone its own (`160`, `[-425, 426, -2, 374]`); copying the phone's values into the tablet is a slip that only shows in the canvas preview, not in the rendered atlas.
- **A test that leaves a folder behind**: `mkdtemp` without a cleanup leaves one folder per run in the temp directory; `self.addCleanup(shutil.rmtree, root, True)` after it, as the other tests in the file do.
- **A force-push needs `--force-with-lease`**: the pull request's branch is shared with the fork.
- **The upstream's CI runs the suite on Windows**: a test that only passes on a Mac breaks the pull request, not the local run.
- **The local mirror is not updated by a pull request**: syncing this checkout's `main` with the upstream is its own line of work (the hub change's "upstream sync" tasks), not a side effect of the contribution.
