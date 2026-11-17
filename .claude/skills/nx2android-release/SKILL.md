---
name: nx2android-release
description: How to cut a release of nx2android (the phone app's APK): the version and the tag's shape, the changelog and the bilingual release notes, the tag push that starts the build, the pre-release and the "Latest" release, the community credits, and the checks a release needs before and after it goes out. Use whenever the task is to publish a release, bump the version, write the changelog or the release notes, re-run a release build, decide which release is the current one, or answer "is it ready to ship?". The rules themselves are in the repository root's AGENTS.md §3; this is the procedure.
---

# nx2android: cutting a release

The product is the phone app: a release carries **the APK and nothing else**. The release's rules are in [AGENTS.md](../../../AGENTS.md) §3 (the one truth) and the version's story in [docs/DEVELOPMENT_GUIDE.md](../../../docs/DEVELOPMENT_GUIDE.md) (Releases and version numbers). This is the procedure, and the traps that come with it.

## The version

| What | Where | How it reads |
|---|---|---|
| The base | `porter/__init__.py` (`__version__`) | the upstream version the line carries (`0.3.0`) |
| The tag | the release | `v<base>-alphaNN` (`v0.3.0-alpha06`), or `v<base>` for the released version of the base |
| The app's own version | `android/app/build.gradle.kts` | the tag without the `v` (`0.3.0-alpha06`) — the update check compares the two |
| The build number | the same file | `versionCode` goes up by one per release, so a build installs over the one before it |

The base changes only when the line picks up a new upstream base; an alpha never changes it.

## Before the tag

- **The tests**: `python -m unittest discover tests` (the tree's suite) and `cd android && ./gradlew :app:testDebugUnitTest` (the app's). CI runs both as well.
- **The changelog**: `CHANGELOG.md` at the repository root, one section per release, newest first. English, plain sentences ("Before you update" first, then New / Fixed / Community ports). Write for a player: what they will see, not what a commit did.
- **The release notes**: the text of the release itself, and **the app shows it in the update popup** — so it is read by players. **Bilingual: English first, Chinese after.**
- **The community credits**: a `Community ports` section naming the pull requests merged since the last release. Take the list with `python3 devtools/release_contributors.py --since=<the previous release's tag>` and paste it. The script's default baseline is the newest tag, so run it **before** the new tag is pushed (or pass `--since`). Two things it does not see, and the notes need by hand: the pull requests of **this** repository that were merged **upstream** and came back with the upstream sync (read the upstream merge commits' "Merge pull request #N from \<handle\>" lines — `git log <last tag>..HEAD --format=%B | grep "Merge pull request"`), and the games' own authors (`git log --format="%an" -- <the profile>`). Write the handle the pull request was opened with.
- The script takes `--since=<tag>` (with the equals sign) and it lists this repository's pull requests only.
- **The device**: a change to a port, the host or the app needs the device (AGENTS.md §4). A release checks only the games it adds or fixes.

## The steps

```bash
# 1. the version and the changelog
git add CHANGELOG.md android/app/build.gradle.kts
git commit -m "chore(release): version 0.3.0-alpha07"
git push origin android

# 2. the tag: this starts the build
git tag -a v0.3.0-alpha07 -m "The Android line's seventh alpha ..."
git push origin v0.3.0-alpha07

# 3. the release: the build waits for it and attaches the APK
gh release create v0.3.0-alpha07 --verify-tag --prerelease \
    --notes-file <notes> --repo zFitness/nx2androidX
```

- **Only a tag builds** (`.github/workflows/android-apk.yml`): a push of the branch builds nothing, and the build that the tag starts comes from the tag's own commit. It waits for the release to appear and attaches `nx2android-<tag>.apk` to it (a release that never appears leaves the APK in the run's artifact).
- **A pre-release is published with `--prerelease`** so it does not become the repository's "Latest" release; the app's update check reads "Latest".
- **The release carries the APK and the game profiles package**: the app does not carry the profiles (half a gigabyte of shader tables), so the player imports the package into their own folder (the app's environment check has the button). The same build cuts it (`devtools/profiles_zip.py`) and attaches `nx2a-profiles-r<revision>-<commit>.zip` to the release; the Quark Drive folder holds it beside the APK.
- **No desktop bundle by hand**: the desktop bundles are built by hand when they are wanted (Actions → the workflow → Run workflow).
- Every `gh` command takes `--repo zFitness/nx2androidX`: `gh` in this checkout defaults to another repository.

## The QQ group's announcement

The tag's build tells the QQ group about the release, after the APK is attached to it: `devtools/qq_release_announce.py` posts the version, the Chinese changelog (the release's own notes), the release's page and the netdisk link to the group.

- **It is an extra**: that step is `continue-on-error`, so a refused token, a group that took the bot out or a link the platform refuses never fails the release's build. The announcement's failure is a line in the run's log, nothing more.
- **The bot's tickets are the repository's secrets**: `QQBOT_APP_ID`, `QQBOT_APP_SECRET`, `QQBOT_GROUP_OPENID`. Without them there is no announcement and the release goes out as it always did.
- **The groups**: `QQBOT_GROUP_OPENID` holds one group or several, separated by commas. The announcement goes to each of them, one by one: a group that refuses it (the bot is not a member any more) does not hold the others back.
- **The group's id is found once, by hand**: `python3 devtools/qq_release_announce.py listen` (it needs the QQ bot SDK locally: `pip install qq-botpy`), then add the bot to the group or @ it there and read the id it prints.
- **The announcement is short by design**: at most four lines of the changelog, each cut to 42 characters.
- To see what it would say: `python3 devtools/qq_release_announce.py announce --tag <tag> --dry-run`.
- The release's notes carry a `<!-- qq-announced -->` marker afterwards, so a rebuilt tag does not announce the same version twice.

## Watch the build

```bash
gh run list --repo zFitness/nx2androidX --limit 4
```

The run's **title is the tagged commit's subject**, not the tag's name (the tag is in the "tag/branch" column) — a run titled `chore(release): version …` on `v0.3.0-alpha07` is that release's build.

## Check the artifact

```bash
gh release view v0.3.0-alpha07 --repo zFitness/nx2androidX \
    --json assets --jq '.assets[] | "\(.name)  \(.digest)"'
gh release download v0.3.0-alpha07 --repo zFitness/nx2androidX \
    -p "nx2android-v0.3.0-alpha07.apk" -D /tmp/rel

shasum -a 256 /tmp/rel/*.apk                       # the same digest as the release's
~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs /tmp/rel/*.apk
~/Library/Android/sdk/build-tools/36.0.0/aapt2 dump badging /tmp/rel/*.apk | grep '^package:'
```

- The digest is the one the release's own API gives.
- The certificate is the project's key, `CN=nx2android`, SHA-256 `564AB303…2F5` — the same one every release carries, so a build installs over the one before it.
- `versionName` is the tag without the `v`; `versionCode` is one higher than the last release.
- The APK is not debuggable, and the payload holds the rootfs and the .NET runtime.

## Re-run a build

**Push the tag again** — the build follows the tag, so the artifacts come from the tag's commit:

```bash
git tag -f -a v0.3.0-alpha07 -m "..." && git push --force origin v0.3.0-alpha07
```

A build of a tag started by hand (`gh workflow run android-apk.yml --ref v0.3.0-alpha07`) attaches to the release as well.

The old way round — putting the release back to a draft and publishing it again — **no longer triggers a build** (the build is the tag's), and it drops the pre-release flag unless the publish carries `--prerelease=true`. The hook `.claude/hooks/release_guard.py` refuses that.

## Make an alpha the current version

GitHub does not let a pre-release be the "Latest" release (the API says *drafts and prereleases cannot be set as latest*), so the way to make an alpha the version everyone gets is to take the pre-release mark off it:

```bash
gh release edit v0.3.0-alpha07 --prerelease=false --repo zFitness/nx2androidX
gh release list --repo zFitness/nx2androidX --limit 6          # the "Latest" row
curl -sI https://github.com/zFitness/nx2androidX/releases/latest | grep -i location
```

**Taking the mark off is not always enough**: GitHub decides the "Latest" when the release is created (`make_latest`), so a release that was published with `--prerelease` keeps the old "Latest" after the edit. Point it by name:

```bash
gh api -X PATCH repos/zFitness/nx2androidX/releases/<id> -F make_latest=true
gh api repos/zFitness/nx2androidX/releases/latest --jq .tag_name     # the tag it answers with
```

The `curl` above is served from a cache: add a query (`?nocache=1`) when it still shows the old tag.

The name in the tag keeps its alpha; the next pre-release does not take that place on its own. Which release is the current one is written down in AGENTS.md §3.

## The traps

- **The tag is what the artifacts are built from.** A fix after the tag does not reach the APK: move the tag and push it again.
- **A fork's pull request needs its CI approved** (`action_required`), and merging closes that run before it starts — the release's own build is then the pull request's verification. Say so in the release's notes if the change is in the payload.
- **The notes are read by players**, in the app's update popup: plain sentences, no jargon, and both languages.
- **A release checks only the games it adds or fixes** (AGENTS.md §3).
- **Before the APK is shared outside the maintainers, the repository has to be public**: the licence (GPL-3.0-or-later) requires the people who receive it to get the source.
- **The update check compares the tag with the app's `versionName`**: if the two disagree, the app either never says anything or always says something.

## After the release

- The device check of the release itself (the APK installed over the last one, the version in the settings) is still open until a phone is connected.
- The APK goes to the Quark Drive folder **我的/nx2AndroidX**, and the older versions there are cleared: [nx2android-quark-upload](nx2android-quark-upload/SKILL.md).
