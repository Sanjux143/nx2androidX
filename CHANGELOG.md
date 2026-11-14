# Changelog

The releases of nx2androidX, newest first. Each one carries the phone app's
APK, signed with the project's own key, so a build installs over the one
before it. The pre-releases (`-alphaNN`) are published as GitHub
pre-releases: they do not become the repository's "Latest" release, so the
players on the released version are not pushed onto an alpha.

The release page has the APK and the full notes: <https://github.com/zFitness/nx2androidX/releases>.

## Unreleased

### Changed

- **The whole repository is now GPL-3.0-or-later.** It was MIT, with the frame generation as the one GPL component in the tree. The MIT terms let anyone take the code and ship a closed app from it; the GPL terms require a derivative to be distributed under the same license, with its source and the notices kept. The license follows the code that was taken, not what the build linked: the frame generation can be left out of a build (`-PnxLsfg=false`), and the result is still GPL. The third-party components keep their own licenses, the upstream engine's copyright notice is kept, and the full license text travels with the app and the desktop bundle.
- **The license files are laid out so that a machine reads them.** `LICENSE` is the GPL-3.0 text alone, so GitHub shows the repository as GPL-3.0 instead of "Other"; `NOTICE.md` says what those terms cover, who holds the copyright on what (the upstream engine and the upstream desktop tool are named, with their MIT text quoted), and which parts keep a license of their own. The app's own notice no longer says that selling it is not allowed: the GPL lets a distributor charge for a copy, so the notice says what the license actually says.

## v0.3.0-alpha10 - 2026-11-13

### Before you update

- Nothing to redo: the player API did not move, so the data packs of alpha09 keep working.

### New

- FINAL FANTASY RESONANCE (Asian release) plays on Android now: its own profile, its own host rows, its own gamepad theme. Unreal Engine 5.6.1, 73,512 shaders, all translated. The international release is untouched.
- The older pack of the same game goes when the new one lands: its saves move into the new pack first, and two packs that both hold saves are left alone.
- The cover is read again when it failed (the card showed a letter instead of the game's own picture until the app was restarted), and a pack that is only a manifest never wins the card.

## v0.3.0-alpha09 - 2026-11-13

### Before you update

- The player API moved to revision 2 (the frame rate step), so a pack made with an older one is marked as needing a runtime update. "Update runtime" in the library rebuilds it in place: the game does not have to be ported again, and your saves are kept.

### New

- The shared runtime log is a bundle now: the device's and the app's own facts, the game's own facts (its name, title ID, version, profile status) and the host's logs of the last run and the one before it. The host renames its log at every start, so a crash's backtrace was in the log the share never took.
- A frame rate step in the drawer: follow the game, 60, 40 or 30. The pick takes effect at once and is remembered per game. A game capped at 30 fps stays at 30.
- The drawer has a performance page now - the first one, and the one it opens on: the frame rate step and the docked mode, each with a line saying when it helps.

## v0.3.0-alpha08 - 2026-11-13

### Before you update

- Nothing to redo: the data packs of alpha07 keep working, and a game that
  needs a new runtime is still updated on its own from the library.

### New

- "Share the log" packs a zip now: the device's and the app's own facts, the
  app's log, the stage logs of every game in the work folder (the host's
  build, the Oodle decoder's run, the extractor's, the shaders', the porter's
  traceback) and the build history's logs. Each log says how much of it was
  cut.
- A game's updates and its DLC are found in a first-level subfolder next to
  the base game, so the usual layout of a download now ports with its DLC.
  Only the picked game's files are taken from there, and the report says
  which subfolder each update and DLC came from.
- The builds page says what it found and how long it runs: the target (which
  game, which file, is it adapted), the files the porter found (the base
  game, its updates and its DLC, each with its version and the subfolder it
  came from) and the build options.

## v0.3.0-alpha07 - 2026-11-13

### Before you update

- Your data packs do not have to be ported again. If a game's runtime is not the current one, the library says so, and "Update runtime" rebuilds it from the profile that made the pack. Your saves are kept.
- This release carries the upstream line's work: the engine's own fixes and nine more games that play on Android.

### New games

- Animal Crossing: New Horizons (local play over the LAN)
- DARK SOULS: REMASTERED
- FINAL FANTASY RESONANCE
- Kirby and the Forgotten Land
- Lonely Mountains: Downhill
- Octopath Traveler 0, and its Chinese release with a gamepad of its own
- Picross S+
- South Park: The Stick of Truth
- The Legend of Zelda: Echoes of Wisdom

### New

- A data pack's runtime can be updated on its own. The app rebuilds it from the profile that made the pack, so a game whose runtime is out of date does not have to be ported again.
- Local play over the LAN: the console's own local play, without the console.
- Games that use OpenGL ES run on the device's own GL driver.
- Unreal's shader archives are decoded on the device, with the game's own Oodle decoder.
- The app's code is split into layers now, and follows the repository's own layout.
- The builds page says what the selected game holds.

### Fixed

- The memory pools are redone: they are allocated outside the pool lock and the small ones live in slabs, so the frame time does not jump when a pool appears or goes.
- The Vulkan pipeline caches are checked against a copy on the CPU before they are used, and they are saved atomically: a cache that went wrong is rebuilt instead of drawing the wrong thing.
- A 32-bit game stops with a clear message instead of failing later.
- The Unreal shader decode continues with the streams it has.
- The pak's AES works without commoncrypto, and with pycryptodome's other name.
- The top bar's chip is coloured by the child-process limit.
- A save package that holds no file is refused.
- The setup checks the battery setting.

### Community ports

- No community pull request has been merged since the last version.
- Everyone in this release's history is named in the README's contributors table.

## v0.3.0-alpha06 - 2026-11-11

### Before you update

- The data packs are untouched this time: the games you ported with alpha05
  play as they are, no porting again.
- The output folder setting is gone: the data pack lives in the app's own
  folder. A pack made on a computer does not play on the phone any more - the
  game is ported on the phone (the shaders are reused, so it is quick).

### New

- The data pack can live on a removable card: one button in the library moves
  it (with a progress bar), and a rebuild writes the new pack on the card as
  well. Pulling the card during a move leaves the pack where it was; a
  deletion with the card out says that the pack on the card was not deleted.
  Verified on a device (a Xiaomi tablet and a memory card).
- The library clears the build cache and deletes a game: a failed port no
  longer leaves files behind that nothing could get rid of. A deletion takes
  the data pack (and the saves in it) with it, and your own game files stay.
  Both ask first, and the confirmation says what the action does.
- The settings page is a page of sections: an icon in the top bar opens it,
  five sections on the left, one row per setting. The settings themselves did
  not change; the language moved into About.
- The update dialog spreads over the available width on a small screen.

### Fixed

- Picking a Lossless.dll from anywhere failed to copy: only the path that
  finds it on its own made the folder, and the failure was reported as "this
  does not look like a Lossless.dll". The folder is made now, and a copy that
  does not work is logged.
- The frame generation row has one button: the private copy refreshes itself,
  so the button that refreshed it by hand only repeated what the app did.

### Community ports

- **@15622162922** — #10: fix(android): compile adrenotools C hooks with clang
  The Vulkan driver loader's hooks keep their names now: they were built as
  C++, so the names came out mangled and the loader could not find them.

## v0.3.0-alpha05 - 2026-11-11

### Before you update

- Data packs made with an older version do not work any more. Port your games
  again - the library marks them, and the button says "port again".
  - The shaders are not translated again: the ones that did not change are
    reused. Only the host's glue layer is rebuilt, so it is much faster than
    the first port.
  - Your game progress and your exported save backups are kept.
  - The game's files have to be in the same folder as before.

### New

- Faster ports. The data pack is written to the app's own folder instead of
  shared storage: ~200-230 MB/s instead of ~50-100 MB/s. Three Tears of the
  Kingdom ports died at that copy step before.
- Performance overlay over the game: FPS, frame time, CPU, GPU, memory, battery
  temperature, power. You choose which fields show.
- New in-game menu. It opens from the left edge with the gamepad's menu button
  or the back gesture. Pause / resume / exit are in it.
- The library shows the game's own name and version, not the file name.
- "Extra address space" on the build page. It fixes "AllocateAddressRegion
  refused (0x1803)" in some games.
- The environment's state is on the top bar of every page. Tap it to see what
  is missing.
- The on-screen gamepad is drawn by the app now. The layout editor is new, and
  the d-pad lights up the direction you press.
- The update check opens a popup with the new version, the version you run, and
  the release's own notes.

### Fixed

- The app update replaces the tool tree instead of writing over it. An update
  that left the old files behind built two hosts at once, and every port
  failed.
- The update check runs on its own: an error there no longer closes the app.
- The frame generation no longer slows frame-locked games down. Measured on a
  Y700 (120 Hz): 60 -> 120 fps.
- The frame generation can be turned off from the in-game menu. The switch only
  turned it on before.
- Without Lossless.dll the switch says it is not available, instead of looking
  like an ordinary off switch.
- Typing in a game no longer freezes and then crashes it.
- A crash writes a log now instead of dying without one.
- The hidden on-screen gamepad no longer blocks touches on the game.
- After a game closed, the library no longer flashed "back to game / exit game".

## v0.3.0-alpha04 - 2026-11-10

### Added

- **The app's data pack is built in the app's own folder** (`files/ports`), not on the shared storage. The extracted game files were copied over FUSE, which is slow (50-100 MB/s) and fragile; the copy now runs at the device's write speed (200-230 MB/s measured) and no media scanner shares the files. The log of 2026-11-09 shows three ports of Tears of the Kingdom dying in that stage: the system killed the app, a half-made pack left behind made the next port fail, and a cancel interrupted a read. The game reads its files from there as well.
- **The free-and-open-source notice.** It is shown once, when the setup finishes, and on the first start of an app that had already done the setup. It says the app is free and open source, that selling it is not allowed, and that a player who paid for it can ask the seller for a refund. "View the repo" opens the fork's page on GitHub.
- **Tapping the version in the Settings opens the repository on GitHub.** The underline says the row can be tapped; a device without a browser has no other way there, so the app stays quiet.
- **An experimental port says so.** The data pack's manifest carries the profile's own status: a game that was never brought up on Android is written with the status experimental, and the library shows it as one - the player is told the port may stop at a path that was never walked.
- **A developer setup guide** (`docs/GETTING_STARTED.md`).

### Fixed

- **A game's text entry froze the game and then killed it.** The host told the app "the drawer steps aside" with a JNI call made on the game's own thread, and that call blocked there: the log showed the entry opening and nothing after it, and the process died of SIGSEGV with no backtrace and no tombstone. The notice goes on a thread of its own now - the game's thread must never wait for the app.
- **A crash on Android was silent.** The crash handlers were only installed by the desktop host, so a SIGSEGV killed the game silently: the kernel had nowhere to run a handler, the process died of the signal and the log stayed empty. They are installed now, and every thread that can fault gets the signal alternate stack.
- **The log said the wrong reason.** The line read "audio suspended (background / interruption)" every time the game was held, but the game is held for three reasons: the app is in the background, the pause menu is open, or a text entry is open. A game's name entry froze with the keyboard never coming up, and the log blamed the background. The host names the reason now.
- **The app checks the all-files permission before a port starts.** Without it the game file cannot be read, and the port died in the middle of a Python stack trace.
- **The cleanup of a half-made pack is tolerant now.** The folder may be gone already (another process removed it), and a directory that vanishes while it is being removed must not stop the port - that was the FileNotFoundError that ended with exit 139.

## v0.3.0-alpha03 - 2026-11-10

### Added

- **The Oodle shader archives are decoded on the phone itself.** The two Pokémon Legends games keep their shaders in Oodle-compressed archives, which have to be unpacked with the game's own decoder. That decoder is an ARM64 program, and the phone is the ARM64 machine it runs on - so the app decodes them there, with no adb and no second device in the loop. Where the port runs, the decoding follows it: a computer with adb decodes on the connected device, the phone app decodes on itself, and a computer with neither says so instead of failing later.

### Changed

- **The README was rewritten**, in English and Chinese: the phone app first, what changed on this branch, and the games that play on Android.

## v0.3.0-alpha02 - 2026-11-10

### Added

- **The versions say what they are.** The tags carry the whole upstream base and this line's own stage on it: `v0.3.0-alpha02` is the base 0.3.0's second alpha, and `v0.3.0` is its released version. The app says the same version, so its update check can tell which release is newer than the build that is running.

### Fixed

- **The port's space check counts the right files.** It measured every archive in the folder, so a folder holding more than one game was sized as if all of it were going to be built, and a port that would have fitted was refused. It counts the picked game's files now: its base, its updates, its DLC.

## v0.3-android - 2026-11-09

The first release of this line. The phone app is the whole tool: it ports a game on the device itself and plays the data pack it makes.

### Added

- **Ports on the device.** The app carries the whole pipeline (the Linux rootfs, the build tools, the .NET runtime) and turns a game's own files into a data pack on the phone, then plays it.
- **The game is a page of the app.** Starting a game no longer opens a second task: the game sits on top of the library page, so "exit the game" falls back to the library.
- **The setup's keys are the ones a port uses.** One path: what the guided setup asks for is what the port starts with.
- **The app speaks the player's language.** Chinese or English, switchable in the settings and effective at once.
- **Saves can be shared.** A save can be packed and sent to someone else, and a package from elsewhere imported - with the old save kept as a backup.
- **Vulkan drivers.** The app ships one driver, downloads more, imports the player's own, and the game picks the one it runs with.
- **The in-game drawer.** The menu button opens it: frame generation and display & controls in the pause menu.
- **The update check on start.** The release page is checked quietly; a newer version says so on the home page, and the Settings tab can check again on demand.
