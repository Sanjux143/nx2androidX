"""APK/player runtime interface revision. Bump only for incompatible interface changes.

2: the host gained the drawer's frame rate step (jni_android.c's perfPinSet/perfPinGet): the app calls them, so a pack's
runtime has to be rebuilt for the step to exist.
3: the host gained the drawer's motion switches (jni_android.c's motionSet/motionGet): the app calls them from the
drawer's Controls tab, so a pack's runtime has to be rebuilt for the motion to exist.
4: the host gained the drawer's audio output and GPU clock switch (jni_android.c's audioSet/audioGet and
gpuTurboSet/gpuTurboGet): the app calls them, so a pack's runtime has to be rebuilt for them to exist.
5: the host gained the gamepad center's table and the release of every key (jni_android.c's padMapping/padReleaseAll):
the app calls them, so a pack's runtime has to be rebuilt for the center to exist.
"""
PLAYER_API_REVISION = 5
