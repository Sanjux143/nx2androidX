<h1 align="center">
  <img alt="nx2android" src="docs/images/logo-badge.svg" height="140">
</h1>

> **English**. 中文文档：[README.zh.md](README.zh.md)

[![Contributors](https://img.shields.io/github/contributors/zFitness/nx2androidX?style=flat&label=contributors)](https://github.com/zFitness/nx2androidX/graphs/contributors)
[![Release](https://img.shields.io/github/v/release/zFitness/nx2androidX?style=flat&label=release)](https://github.com/zFitness/nx2androidX/releases/latest)
[![License](https://img.shields.io/github/license/zFitness/nx2androidX?style=flat&label=license)](LICENSE)

Port your own Switch games to Android, natively: the game's own arm64 code runs on the device, with the Switch's system services stood in for by a host and NVN graphics replayed on Vulkan. Pick the game's file, click **Port**, and get the game's data pack, which the nx2androidX app on the phone runs. Bring your own game files and keys; this repository holds no game content and no keys.

This repository is nx2androidX, built on nx2android — the PC tool that ports a Switch game and packages the APK. nx2androidX moves that to the phone: give it the game's files and it compiles and runs them on the device itself, with no computer in the loop. The games that play on Android are listed upstream: [Games](#games).

The app is the whole tool on the phone: it ports the game on the device and plays the pack it makes. The game files it shows are the player's own.

**This repository's releases hold the phone app itself** (the APK, its file named after the version). The PC tool is released upstream, by nx2android; both routes are in [For players](#for-players-port-a-game-without-programming).

---

## What changed on this Android branch

This branch closed the loop on "playing it on the phone": the app went from a player to the whole porting tool.

### The app is now the whole tool

Porting used to happen on the computer: install the tools, pick the file, click Port, copy the data pack back to the phone. Now the phone starts the port itself — pick a game file on the **Builds** tab, tap **Start a port**, the port runs in the background (the notification bar shows "Port progress"), the data pack lands in the app's own folder, and the game is playable from the library.

![Four screens of the app: starting a port on the Builds tab, a game entry in the library, the in-game drawer's frame generation, and the Settings tab](docs/images/app-screens.png)

*Top left: starting a port on the Builds tab; top right: a game entry in the library; bottom left: the in-game drawer's frame generation; bottom right: the Settings tab (language, environment, keys, Vulkan drivers).*

The library got simpler too: it used to take a folder to be the library, and the scan behind it was recursive — one root directory was a long freeze. Now **the library only holds the games registered on the Builds tab**, the whole folder scan is gone, and an empty library shows one line of guidance instead of buttons.

### What the current release added

| Feature | What it is |
|---|---|
| **Porting on the phone** | Pick a file on the Builds tab and the port starts; it runs in the background and the game is playable when it is done — no computer in the loop |
| **A glibc rootfs** | The app carries a glibc rootfs of its own, and the porting tools are Linux binaries that execute on the device itself: no proot, no root, no compatibility layer in between |
| **Renamed nx2androidX** | The phone app carries the new name; the releases live in `zFitness/nx2androidX` |
| **Onboarding fixed** | It can be finished now, the progress is not lost, and the keys picked in step three are the ones the port uses |
| **The game is a page of the app** | Ending the game falls back to the library instead of the home screen, and the recents hold one entry |
| **The app's own language** | Chinese and English, switchable at any time (the game's own language stays in the library's drawer, one game at a time) |
| **Saves you can share** | An export becomes a package handed to the system share sheet; an import takes one in from anywhere; the save it replaces is backed up first |
| **An in-game drawer** | The ☰ menu button opens it: performance, HUD, frame generation and controls in the pause menu |
| **Frame generation** | Available while the game runs; the multiplier and the target frame rate take effect at once |
| **A steady frame rate** | The drawer's performance page holds the frame rate step (60, 40 or 30 instead of a rate that wanders) and the docked mode |
| **Vulkan driver management** | Download (Turnip builds), import from a file or remove, in the Settings tab; one Turnip build ships inside the app |
| **Update check on start** | The release page is checked quietly; a newer version says so on the home page, and the Settings tab can check again on demand |
| **Shareable logs** | The library's drawer shares the game's runtime log; the Settings tab clears it |
| **APK 460 MB → 272 MB** | The .NET on the phone went from the SDK to the runtime: the two C# tools are pre-built, so the phone only runs them, never builds them |

### Onboarding

The first launch walks four steps: storage access → environment → keys → done. The environment (rootfs, the build tools and the .NET runtime, about 700 MB) is unpacked once; later updates of the app only carry the new small tools, and that step does not repeat. Skipped steps are not fatal either: they can be made up in the Settings at any time, and the library's entry says what is missing.

### Saves you can share

The save actions in the library panel became a backup-and-share affair: an export writes the save as a package into the downloads folder and hands it to the system share sheet; an import takes a package in from anywhere — another phone, a cloud drive, a console's save tools. An import replaces the save the game has, so the app exports that one first (a folder ending `-before-import`), and the panel lists every package and backup, to restore, share or delete.

An import is not taken on trust: a package carrying another game's title ID is refused, a path pointing out of the folder is refused, and the unpacked size is capped.

### The in-game drawer and frame generation

The ☰ menu button opens the in-game drawer, with four tabs: **performance**, **HUD**, **frame generation** and **controls**.

The performance tab holds the two trade-offs: the **frame rate step** — *follow the game* leaves the rate to step between 60, 40 and 30 fps on its own, while 60, 40 or 30 hold that step, so the rate stays steady where the device can hold it (the pick is kept per game and takes effect at once) — and the **docked mode**, which renders at the TV's resolution: a sharper picture that costs more power, and a higher frame rate without it.

Frame generation uses Lossless.dll: the frames-per-game multiplier, the target frame rate and the optical flow quality are all adjustable, and the switch and the multiplier take effect at once; an updated DLL is copied to the private folder by itself. Lossless Scaling's resources are neither bundled nor downloaded, and the DLL is the player's own file: we do not distribute it and do not link to a download.

The HUD tab has the on-screen information: the performance line (the frame rate counter), the device line, the fields the line shows and the temperature's unit.

The controls tab has the virtual gamepad: the layout editor (drag a control, pinch to resize, snap to grid), the pad's opacity and the rumble.

### Vulkan drivers on the phone

The drivers are the player's, not the pack's: the **Vulkan driver** section of the Settings tab can download them (Mesa's Turnip builds, from their own addresses), import one from a file, and remove one. One Turnip build — the Adreno 8xx one — ships inside the app: it unpacks itself into the app's own folder when the app starts (no network, no click) and is marked as the built-in one, so it is not the player's to remove.

The driver a game runs with is picked in the library's drawer (**Vulkan driver**), the device's own driver first. The choice is kept in the data pack's `env.txt`, so it lasts, and it takes effect the next time the game starts.

---

## Requirements

- **Android 8.0 (API 26) or newer**.
- **1.5 GB of free space to start**: the APK is about 272 MB, the environment unpacked on the first launch about 700 MB, on top of your own game files and the data packs.
- **A GPU that reads BC-compressed textures**: Adreno (Snapdragon) and Xclipse (Exynos 2200 and later) do; Mali and PowerVR do not. The tested device is a Lenovo Legion Y700 (Snapdragon 8 Elite, Adreno 830).
- **Your own game files and `prod.keys`**, dumped from your own Switch: the tool never downloads either.

## Games

The games that play on Android are listed upstream, with the nx2android version each was tested on and the frame
rate it runs at: [Games](https://github.com/shirai91/nx2android#games). The games ported so far, and the ones
next, are in [docs/PORT_CHECKLIST.md](docs/PORT_CHECKLIST.md).

## For players: port a game without programming

### Route one: port on the phone (recommended)

1. **Install the app**: download `nx2android-<version>.apk` from [Releases](https://github.com/zFitness/nx2androidX/releases/latest) and install it with the system's own installer.
2. **First start**: walk the onboarding (storage access → environment → keys → done); the environment step downloads and unpacks about 700 MB.
3. **Builds tab**: tap **Choose a game file**, pick the base game (the updates and the DLC next to it are taken along), then tap **Start a port**. The port runs in the background; the notification bar keeps the progress.
4. **Library tab**: when the port is done the data pack is in the library — tap **Run** and play.

The port's own options sit on the Builds tab too: **Touch game** (the on-screen gamepad starts hidden), **60 fps** and **Experimental port** (a game that is not adapted yet).

### Route two: port on a computer

The PC tool is released upstream, by nx2android; this repository's releases hold the APK only.

1. Download `nx2android-<version>-windows-x64.zip` (Windows), `nx2android-<version>-macos-arm64.zip` (Mac with Apple silicon), `nx2android-<version>-linux-x64.zip` (Linux on a PC) or `nx2android-<version>-linux-arm64.zip` (Linux on ARM) from the upstream [nx2android Releases](https://github.com/shirai91/nx2android/releases/latest), unzip it and start **nx2android** (on a Mac, see [packaging/INSTALL.md](packaging/INSTALL.md#1-start-the-app) first).
2. **Setup** tab: click **Download missing tools** (the Android NDK, the .NET SDK and platform-tools, about 1 GB, from Google and Microsoft), then set your **prod.keys**. Every line turns green.
3. **Port** tab: choose the folder with the game's NSP, XCI, NSZ or XCZ files (base game, update, DLC) and click **Port**.
4. The port leaves the game's data pack (`<Game>-data/`) in the game folder. The phone app keeps its own data packs in its own private folder and does not read that one — to play on the phone, port the game on the phone (route one).

   A computer is not needed at all: the app ports the game itself, see route one.

### Common switches

- **A game played by touch**: tick **Touch game** on the Builds tab (phone) or the Port tab (PC, `--touch`). The on-screen gamepad then starts hidden and every touch but one on the menu button goes to the game; the pause menu shows the gamepad again.
- **A game slowed by its draw calls in busy scenes**: tick **Multithread replay** on the Port tab (`--mt-replay`). The game's Vulkan commands are then recorded on a thread of their own. It stays on until you port the game again without it.
- **To arrange the on-screen controls**: open the pause menu's **Gamepad** tab and choose **Edit layout**: drag a control to move it, pinch or use **− / +** to resize it. **Save** keeps the layout for the next launch and **Cancel** discards your edits. **Snap to grid** (off at first, and remembered) shows a square grid and snaps each control's center to it as you drag.
- **The game opens from the app's library page**: it shows the game, where its files are, and the language and the Vulkan driver to run it with. The main action starts the game, and the choices are kept in the data pack's `env.txt`, so they last.
- **To run a game with a Vulkan driver the device's own does not** (Mesa's Turnip on Adreno GPUs): set **Vulkan driver folder** on the Port tab (`--vulkan-driver-dir`); the pack carries them all and the app lets the player pick one, the device's own driver being the first choice. On the phone the drivers are managed in the app's own **Vulkan driver** section — see [Vulkan drivers on the phone](#vulkan-drivers-on-the-phone) above.
- **A game that crashes with "AllocateAddressRegion … refused (0x1803)" in its log** needs more address space: raise **Extra address space** on the Port tab (`--alias-headroom` on the command line), for example 16 GB, then 24 GB. [packaging/INSTALL.md](packaging/INSTALL.md#when-something-goes-wrong) says more.
- **The app looks for a newer release too**: when it starts, it checks the release page quietly, and if there is a newer version it opens a popup that says which version is out and what changed in it. **Later** puts it away until the next start; the **Settings** tab has a **Check for updates** entry that checks again on demand. The app downloads nothing by itself — the popup leads to the release page, where the APK is.
- **Pokémon Legends: Arceus and Pokémon Legends: Z-A keep their shaders in Oodle-compressed archives**, which only the game's own decoder can open. On the phone the port decodes them here — the phone is the ARM64 machine that runs the game's own code — so nothing else is needed. On the desktop tool the same decode runs on a device you plug in: have platform-tools (adb) from the Setup tab, and connect the device with USB debugging on before you click **Port**.
- **Chinese or Korean text drawn as `???`** (the PC tool): choose a TrueType font with those characters as **System font** on the Port tab (`--shared-font`, a single `.ttf`, not a `.ttc`). It goes in the data pack; the tool ships none.
- **A game that reads system data from the console's firmware** (`sysdata` in its profile; Mario Kart 8 Deluxe builds its Mii heads from MiiModel): the PC tool cannot ship the console's own system content files. Copy them (`.nca`) to a folder on the PC and give it with `--firmware <folder>` (or `porter config firmware <folder>` once). Without it the host's empty stand-in is used.
- **The game is already on the device for an emulator** (the PC tool): the app can read the game's files (its RomFS, most of its size) from that dump's folder instead of a second copy: type the dump's RomFS folder in the app's **RomFS on the device** field (or give it to `--romfs-at`) before you install, which must be the same game version as the port.

## Saves

**On the phone**: the library page → the drawer → **Saves**. **Export the saves** writes the save as a zip package into the download folder and hands it to the system share sheet; **Import the saves…** takes a package in from anywhere. The drawer also holds **Save packages** (the packages you shared) and **Backups** (the automatic backup of an import, which can be restored).

An import replaces the save the game has, so the app keeps that one first (a folder ending `-before-import`). A package carrying another game's title ID is refused, and the unpacked size is capped. The game has to be closed before its save is touched — the host writes it while it runs. Uninstalling the app deletes its saves and its exports: copy them out first.

**On a computer** (the desktop tool's **Saves** tab): the saves travel between the device and the PC over adb. An export is a folder in `Android/data/<package>/files/saves/` on the device, the save's files as a console's save tools copy them, so a save from a console imports the same way.

## For developers

Start here: [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) — the tools to install, the two working loops (the desktop tool and the phone app), the tests and the release steps.

Running from the repository, the command line, the repository layout, building the bundle, tests and commit messages: [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md). How it is put together: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Contributing

Contributions are welcome, and the first one does not have to be code.

- **A game runs on your device?** Open a **Game report** issue: the game, your device, the version and the frame rate. It puts the game on the list, with your name on it.
- **A game does not run yet?** Open a **Port request** issue. Some games cannot be ported at all; the issue says which ones, so the next person does not lose an evening over it.
- **A game to port?** [docs/PORTING_GUIDE.md](docs/PORTING_GUIDE.md) — the whole flow, on the phone or on a computer, and no computer is needed at all.
- **Code, docs or translations?** [CONTRIBUTING.md](CONTRIBUTING.md) — the rules, the repository map, the tests and the commit messages.

A question that fits no template is welcome too: open an issue and label it `question`. A game that runs goes through the **Game report** form, a game that does not through the **Port request** form.

## Keys and game files

Reading your game files while it ports them needs your `prod.keys` — the keys your own console uses. This repository and its releases contain no keys, no game files, no firmware and nothing extracted from them; the tool never downloads any of them, and this project provides no method or link to obtain keys. Dump both from your own Switch, and don't share them or the data packs built from them.

The tool is for running personal backups of the games you own. How you obtain the game files and the keys, and whether that is legal where you live, is for you to judge and to answer for.

The game profiles under `nxnative/dm/games/` hold interoperation metadata only: the hashes that identify a game's shaders, the offsets inside a module, and patch stubs of a few bytes of machine code that make a particular game build run. They are the information the host needs to work with a game, not the game itself.

## Contributors

Everyone who is in this repository's history, and everyone who comes with a pull request: a game brought up, a device bug fixed, a document corrected. A report that a game runs counts too.

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tbody>
    <tr>
      <td align="center" valign="top" width="14.28%"><a href="https://github.com/shirai91"><img src="https://avatars.githubusercontent.com/u/8848513?v=4&size=100" width="100px" height="100px" alt="Tung Pham"/><br /><sub><b>Tung Pham</b></sub></a><br /><a href="https://github.com/zFitness/nx2androidX/commits?author=shirai91" title="Code">💻</a> <a href="https://github.com/zFitness/nx2androidX/commits?author=shirai91" title="Documentation">📖</a> <a href="https://github.com/zFitness/nx2androidX/commits?author=shirai91" title="Tests">⚠️</a> <a href="https://github.com/zFitness/nx2androidX/pulls?q=is%3Apr+reviewed-by%3Ashirai91" title="Reviewed Pull Requests">👀</a></td>
      <td align="center" valign="top" width="14.28%"><a href="https://github.com/zFitness"><img src="https://avatars.githubusercontent.com/u/41500499?v=4&size=100" width="100px" height="100px" alt="zfitness"/><br /><sub><b>zfitness</b></sub></a><br /><a href="https://github.com/zFitness/nx2androidX/commits?author=zFitness" title="Code">💻</a> <a href="https://github.com/zFitness/nx2androidX/commits?author=zFitness" title="Documentation">📖</a> <a href="https://github.com/zFitness/nx2androidX/commits?author=zFitness" title="Tests">⚠️</a> <a href="https://github.com/zFitness/nx2androidX/pulls?q=is%3Apr+reviewed-by%3AzFitness" title="Reviewed Pull Requests">👀</a></td>
      <td align="center" valign="top" width="14.28%"><a href="https://github.com/15622162922"><img src="https://avatars.githubusercontent.com/u/49612681?v=4&size=100" width="100px" height="100px" alt="15622162922"/><br /><sub><b>15622162922</b></sub></a><br /><a href="https://github.com/zFitness/nx2androidX/commits?author=15622162922" title="Code">💻</a></td>
    </tr>
  </tbody>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

## License

The code this repository owns and maintains — the porter, the desktop app, the phone app, the packaging, the docs and the nxnative engine tree this fork maintains — is GPL-3.0-or-later: [LICENSE](LICENSE). [NOTICE.md](NOTICE.md) says what those terms cover, who holds the copyright on what, and which upstream code this repository continues. The third-party code in the tree keeps its own license; every component is listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The phone app's frame generation ([nxnative/dm/android/lsfg/](nxnative/dm/android/lsfg/)) is GPL-3.0-or-later as well, the same terms as the rest of the tree. It can be left out of a build, but that does not change the license of the code the build was made from. What that means for the app is in [android/NOTICE.md](android/NOTICE.md).

## Acknowledgements

This project is one branch of a family, and it stands on other people's work:

- **[nx2android](https://github.com/shirai91/nx2android)** — the project this repository is built on: it ports a game on the PC and packages the APK; this repository is its phone side.
- **[seedds/nxnative](https://github.com/seedds/nxnative)** — the engine underneath it: the host that stands in for the console's services, the NVN-on-Vulkan replay, the shader translator, and the game profiles that many of these ports were brought up against. MIT licensed upstream; our fork of it, which lives in `nxnative/`, is GPL-3.0-or-later like the rest of this repository.
- **The nxnative contributors** — for the engine, the tooling and the profiles.
- **[NX2Apple](https://github.com/LNPANDY/NX2Apple)** — the Apple route of the same engine: the macOS builder and the shared iPhone/iPad library. Thanks for keeping the line alive on Apple devices.
- **[BananaNative](https://github.com/Raphix91/BananaNative)** — the branch that takes the engine to Android and runs a few Switch 2 games natively on a phone. Thanks for taking it further than we did.
- **[Mesa](https://www.mesa3d.org/)** — for Turnip, the Vulkan driver the app can download for Adreno GPUs.
- **Lossless Scaling** — for Lossless.dll, the frame generator the app can use; its resources are neither bundled here nor downloaded by us.
- **Everyone who filed an issue, sent a patch, wrote a game profile or translated the docs** — this branch stands on their work too.

Everything shipped in the tree keeps its own license; the index is [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Trademarks

Switch, NVN and the game names in this README are trademarks of Nintendo and of the respective publishers and developers; they are used here only to identify the games this tool is compatible with and to explain technical details. The screenshot is of the app itself, running the player's own game files. This project is not affiliated with, endorsed by or sponsored by them.
