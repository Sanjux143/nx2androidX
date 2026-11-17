"""APK/player runtime interface revision. Bump only for incompatible interface changes.

2: the host gained the drawer's frame rate step (jni_android.c's perfPinSet/perfPinGet): the app calls them, so a pack's
runtime has to be rebuilt for the step to exist.
3: the host gained the drawer's motion switches (jni_android.c's motionSet/motionGet): the app calls them from the
drawer's Controls tab, so a pack's runtime has to be rebuilt for the motion to exist.
4: the host gained the drawer's audio output and GPU clock switch (jni_android.c's audioSet/audioGet and
gpuTurboSet/gpuTurboGet): the app calls them, so a pack's runtime has to be rebuilt for them to exist.
5: the host gained the gamepad center's table and the release of every key (jni_android.c's padMapping/padReleaseAll):
the app calls them, so a pack's runtime has to be rebuilt for the center to exist.
6: the host gained the Android game mode (jni_android.c's perfGameMode): the app calls it on every resume, so a
pack's runtime has to be rebuilt for the mode to reach the host.
"""
PLAYER_API_REVISION = 6

# The game profiles the player imports (a package of nxnative/dm/games/<title ID>/ folders) into their own folder: what the package was cut from.
# PROFILES_REVISION counts the syncs of upstream's profiles (1: the first sync, seedds/nxnative at PROFILES_COMMIT); PROFILES_COMMIT is the upstream commit the
# profiles come from. The revision is a label, not a rule: the player imports a package once and keeps it, an app update does not touch it.
PROFILES_REVISION = 1
PROFILES_COMMIT = 'bf1e4edd48b448bbe3f8fadab6a14ff1819ac742'
PROFILES_REPO = 'https://github.com/seedds/nxnative'
