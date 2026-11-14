// Android port: the player's entry (the app's :play process). One libdmplay.so per game (build.py --play): the
// host's objects statically linked with this file, so the JNI surface and the host travel together and the
// linker never resolves between two of the player's games. Java (com.nx.android.game.Native) hands over the
// Surface, raw input as snapshots (jni_android.h's dm_java_event — a plain Activity has no AInputQueue), the
// lifecycle and the start parameters; the shared core in front_player.c answers exactly as it does for the
// NativeActivity APK. There are no APK assets here: the gamepad's theme comes from the pack's overlay/
// (front_player.c dm_front_root, overlay_theme.c's file fallback).
#include "dm.h"
#include "jni_android.h"
#include "dm_framegen.h"
#include <android/input.h>

int dm_menu_get(int what);   // front_player.c: the menu rows' current values (0 overlay, 1 opacity, 2 rumble, 4 docked)
void dm_menu_overlay_set(int); void dm_menu_opacity_set(int); void dm_menu_rumble_set(int);
void dm_menu_mode_set(int); void dm_menu_edit_open(void);
void dm_present_sample(uint64_t out[5]);   // gfx_vk_present.c: the performance HUD's counters
char *dm_menu_saves(void); void dm_menu_export(void); int dm_menu_import(const char *name);
int dm_menu_export_note(char *out, int cap); int dm_menu_state(void);
char *dm_pad_layout_json(void); char *dm_pad_state_json(void);
#include <stdlib.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <string.h>

JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_start(JNIEnv *E, jclass c, jstring pack, jstring internal, jstring title_id, jint dpi, jobjectArray env, jobject ctx) {
    (void)c;
    // the launcher's env rows (KEY=VALUE; the frame generation's DM_LSFG* among them) go in before the host's own env.txt reads
    if (env) {
        jsize n = (*E)->GetArrayLength(E, env);
        for (jsize k = 0; k < n; k++) {
            jstring row = (jstring)(*E)->GetObjectArrayElement(E, env, k);
            const char *r = row ? (*E)->GetStringUTFChars(E, row, NULL) : NULL;
            if (r) { char key[128] = {0}; const char *eq = strchr(r, '='); if (eq && (size_t)(eq - r) < sizeof key) { memcpy(key, r, (size_t)(eq - r)); setenv(key, eq + 1, 1); } }
            if (r) (*E)->ReleaseStringUTFChars(E, row, r);
            if (row) (*E)->DeleteLocalRef(E, row);
        }
    }
    const char *p = pack ? (*E)->GetStringUTFChars(E, pack, NULL) : NULL, *i = internal ? (*E)->GetStringUTFChars(E, internal, NULL) : NULL,
               *t = title_id ? (*E)->GetStringUTFChars(E, title_id, NULL) : NULL;
    if (!p || !i) { if ((*E)->ExceptionCheck(E)) (*E)->ExceptionClear(E); LOG("[RED] [player] start without the pack or the app folder"); return JNI_FALSE; }
    int ok = dm_java_start(p, i, t && t[0] ? t : NULL, dpi, E, ctx);
    if (p) (*E)->ReleaseStringUTFChars(E, pack, p);
    if (i) (*E)->ReleaseStringUTFChars(E, internal, i);
    if (t) (*E)->ReleaseStringUTFChars(E, title_id, t);
    return ok ? JNI_TRUE : JNI_FALSE;   // 0: the save import asked to end — Java finishes without starting
}

JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_setSurface(JNIEnv *E, jclass c, jobject surface) {
    (void)c;
    if (!surface) { dm_java_window_drop(); return; }
    ANativeWindow *w = ANativeWindow_fromSurface(E, surface);   // our reference; the window itself passes over owned
    if (!w) { if ((*E)->ExceptionCheck(E)) (*E)->ExceptionClear(E); dm_java_window_drop(); return; }
    dm_java_window_take(w);
    ANativeWindow_release(w);
}

// a motion snapshot: every pointer of the MotionEvent, and the ten axes from a controller (jni_android.h).
// Java sends only touchscreen and joystick/gamepad sources, as the NativeActivity queue would
JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_motion(JNIEnv *E, jclass c, jint source, jint device, jint action, jintArray ids, jfloatArray xs, jfloatArray ys, jfloatArray axis) {
    (void)c;
    dm_java_event e; memset(&e, 0, sizeof e);
    e.type = AINPUT_EVENT_TYPE_MOTION; e.source = source; e.device = device; e.action = action;
    jint n = ids ? (*E)->GetArrayLength(E, ids) : 0;
    jint ni = xs ? (*E)->GetArrayLength(E, xs) : 0, ny = ys ? (*E)->GetArrayLength(E, ys) : 0;
    if (ni < n) n = ni; if (ny < n) n = ny; if (n > 16) n = 16;
    e.pointers = n;
    jint *idv = n && ids ? (*E)->GetIntArrayElements(E, ids, NULL) : NULL;
    jfloat *xv = n && xs ? (*E)->GetFloatArrayElements(E, xs, NULL) : NULL, *yv = n && ys ? (*E)->GetFloatArrayElements(E, ys, NULL) : NULL;
    for (jint k = 0; k < n; k++) { e.ptr[k].id = idv[k]; e.ptr[k].x = xv[k]; e.ptr[k].y = yv[k]; }
    if (idv) (*E)->ReleaseIntArrayElements(E, ids, idv, JNI_ABORT);
    if (xv) (*E)->ReleaseFloatArrayElements(E, xs, xv, JNI_ABORT);
    if (yv) (*E)->ReleaseFloatArrayElements(E, ys, yv, JNI_ABORT);
    jfloat *av = axis && (*E)->GetArrayLength(E, axis) >= 10 ? (*E)->GetFloatArrayElements(E, axis, NULL) : NULL;
    if (av) { for (int k = 0; k < 10; k++) e.axis[k] = av[k]; (*E)->ReleaseFloatArrayElements(E, axis, av, JNI_ABORT); }
    return dm_java_input(&e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_key(JNIEnv *E, jclass c, jint device, jint source, jint code, jint action, jint meta, jint repeat) {
    (void)E; (void)c;
    dm_java_event e; memset(&e, 0, sizeof e);
    e.type = AINPUT_EVENT_TYPE_KEY; e.source = source; e.device = device;
    e.key_code = code; e.key_action = action; e.key_meta = meta; e.key_repeat = repeat;
    return dm_java_input(&e) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_lifecycle(JNIEnv *E, jclass c, jint ev) {
    (void)E; (void)c;
    dm_java_lifecycle(ev);
}

// the drawer's menu rows: the host menu's own state and actions, driven from the app (front_player.c dm_menu_*)
JNIEXPORT jstring JNICALL
Java_com_nx_android_game_Native_menuState(JNIEnv *E, jclass c) {
    (void)E; (void)c;
    int st = dm_menu_state();
    char out[64]; snprintf(out, sizeof out, "%d", st);
    return (*E)->NewStringUTF(E, out);
}
JNIEXPORT jint JNICALL
Java_com_nx_android_game_Native_menuGet(JNIEnv *E, jclass c, jint what) {
    (void)E; (void)c;
    return dm_menu_get(what);
}
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_menuSet(JNIEnv *E, jclass c, jint what, jdouble v) {
    (void)E; (void)c;
    switch (what) {
    case 0: dm_menu_overlay_set((int)v); break;
    case 1: dm_menu_opacity_set((int)v); break;
    case 2: dm_menu_rumble_set((int)v); break;
    case 4: dm_menu_mode_set((int)v); break;
    }
}
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_menuEditOpen(JNIEnv *E, jclass c) { (void)E; (void)c; dm_menu_edit_open(); }
JNIEXPORT jstring JNICALL
Java_com_nx_android_game_Native_menuSaves(JNIEnv *E, jclass c) {
    (void)E; (void)c;
    char *list = dm_menu_saves();
    jstring out = (*E)->NewStringUTF(E, list ? list : "[]");
    free(list);
    return out;
}
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_menuExport(JNIEnv *E, jclass c) { (void)E; (void)c; dm_menu_export(); }
JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_menuImport(JNIEnv *E, jclass c, jstring name) {
    (void)E; (void)c;
    const char *n = name ? (*E)->GetStringUTFChars(E, name, NULL) : NULL;
    int ok = dm_menu_import(n);
    if (n) (*E)->ReleaseStringUTFChars(E, name, n);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL
Java_com_nx_android_game_Native_menuExportNote(JNIEnv *E, jclass c) {
    (void)c;
    char buf[128] = {0};
    dm_menu_export_note(buf, (int)sizeof buf);
    return (*E)->NewStringUTF(E, buf);
}

// The player's pad (android-virtual-pad): the app draws the gamepad, works out what a touch hit and tells the host what the game should see
// (front_player.c dm_pad_input). The layout comes from the host as a small JSON string (front_player.c dm_pad_layout_json): the controls'
// places and sizes, in window heights.
JNIEXPORT jstring JNICALL
Java_com_nx_android_game_Native_padLayout(JNIEnv *E, jclass c) {
    (void)E; (void)c;
    char *j = dm_pad_layout_json();
    jstring out = (*E)->NewStringUTF(E, j ? j : "{}");
    free(j);
    return out;
}

// The app's preferences about the gamepad (shown, opacity): the app is the only one that writes them, the host keeps them as runtime state.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_padPrefs(JNIEnv *E, jclass c, jboolean shown, jint opacity) {
    (void)E; (void)c;
    dm_pad_prefs(shown ? 1 : 0, (int)opacity);
}

// How many controllers are in use (the app watches the system's input devices).
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_padDevices(JNIEnv *E, jclass c, jint count) {
    (void)E; (void)c;
    dm_pad_devices((int)count);
}

// The on-screen gamepad: the app routes the touches and tells the host what the game should see (the buttons, the sticks, the touch points
// that are the game's). The host's own touch handling is gone: the hit test is the app's now.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_padInput(JNIEnv *E, jclass c, jlong buttons, jint lx, jint ly, jint rx, jint ry,
                                          jintArray ids, jfloatArray xs, jfloatArray ys) {
    (void)E; (void)c;
    jint n = ids ? (*E)->GetArrayLength(E, ids) : 0;
    jint ni = xs ? (*E)->GetArrayLength(E, xs) : 0, ny = ys ? (*E)->GetArrayLength(E, ys) : 0;
    if (ni < n) n = ni; if (ny < n) n = ny; if (n > 16) n = 16;
    jint *idv = n && ids ? (*E)->GetIntArrayElements(E, ids, NULL) : NULL;
    jfloat *xv = n && xs ? (*E)->GetFloatArrayElements(E, xs, NULL) : NULL, *yv = n && ys ? (*E)->GetFloatArrayElements(E, ys, NULL) : NULL;
    dm_pad_input((uint64_t)buttons, (int32_t)lx, (int32_t)ly, (int32_t)rx, (int32_t)ry, idv, xv, yv, (int)n);
    if (idv) (*E)->ReleaseIntArrayElements(E, ids, idv, JNI_ABORT);
    if (xv) (*E)->ReleaseFloatArrayElements(E, xs, xv, JNI_ABORT);
    if (yv) (*E)->ReleaseFloatArrayElements(E, ys, yv, JNI_ABORT);
}

// The layout editor (the app's): a control's place and size, the layout's own again, and the save into the pack's settings.txt.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_padLayoutSet(JNIEnv *E, jclass c, jint ctl, jfloat x, jfloat y, jfloat size) {
    (void)E; (void)c;
    dm_pad_layout_set((int)ctl, x, y, size);
}
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_padLayoutReset(JNIEnv *E, jclass c) { (void)E; (void)c; dm_pad_layout_reset(); }
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_padLayoutSave(JNIEnv *E, jclass c) { (void)E; (void)c; dm_pad_layout_save(); }

// The app's own panel is up (the layout editor): the game waits.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_hold(JNIEnv *E, jclass c, jboolean on) { (void)E; (void)c; dm_app_hold(on ? 1 : 0); }

// The performance HUD's counters: the game's frames presented, the frame generation's frames presented, the intervals between the game's frames
// (their sum and their count) and the display's refresh cycle. The app reads two of these a second apart and does the arithmetic.
JNIEXPORT jlongArray JNICALL
Java_com_nx_android_game_Native_perfSample(JNIEnv *E, jclass c) {
    (void)c;
    uint64_t s[5] = {0, 0, 0, 0, 0};
    dm_present_sample(s);
    jlong v[5];
    for (int i = 0; i < 5; i++) v[i] = (jlong)s[i];
    jlongArray out = (*E)->NewLongArray(E, 5);
    if (!out) return NULL;
    (*E)->SetLongArrayRegion(E, out, 0, 5, v);
    return out;
}

// The game's text request (the game waits): the app shows the system's input method over the game.
JNIEXPORT jstring JNICALL
Java_com_nx_android_game_Native_textRequest(JNIEnv *E, jclass c) {
    (void)c;
    char *j = dm_text_request_json();
    jstring out = (*E)->NewStringUTF(E, j ? j : "{}");
    free(j);
    return out;
}

// The player's answer: the text goes back to the game and its thread goes on.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_textResult(JNIEnv *E, jclass c, jboolean ok, jstring text) {
    (void)E; (void)c;
    const char *t = text ? (*E)->GetStringUTFChars(E, text, NULL) : NULL;
    dm_text_result(ok ? 1 : 0, t ? t : "");
    if (t) (*E)->ReleaseStringUTFChars(E, text, t);
}

// The front end's capabilities: the app asks right after loading the host. A host built with an older front has no such entry (the call raises
// UnsatisfiedLinkError), and the app then tells the player that the pack has to be ported again.
JNIEXPORT jint JNICALL
Java_com_nx_android_game_Native_frontCaps(JNIEnv *E, jclass c) {
    (void)E; (void)c;
    return dm_front_caps();
}

// the drawer's frame generation: the live switch and the status line ("<state> <reason>", Banana's format)
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_frameGenSet(JNIEnv *E, jclass c, jboolean on, jint mult, jint target, jint flow) {
    (void)E; (void)c;
    dm_fg_set(on ? 1 : 0, mult, target, flow);
}

JNIEXPORT jstring JNICALL
Java_com_nx_android_game_Native_frameGenStatus(JNIEnv *E, jclass c) {
    (void)c;
    char buf[256] = {0};
    int st = dm_fg_status(buf, (int)sizeof buf - 16);
    char out[300]; snprintf(out, sizeof out, "%d %s", st, buf);
    return (*E)->NewStringUTF(E, out);
}

// The drawer's frame rate step: the adaptive rate (perf_android.c) steps 60 -> 40 -> 30 on its own, and DM_FPS_PIN holds one of those steps.
// fps_pin reads the variable again every 30 frames, so setting it here takes effect at once. 0 = free again (the rate adapts), 60/40/30 = hold that step.
// The app calls this after the game starts: the pack's env.txt is read while the host boots, and the later setenv is the one that stays.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_perfPinSet(JNIEnv *E, jclass c, jint fps) {
    (void)E; (void)c;
    char v[8];
    snprintf(v, sizeof v, "%d", (int)fps);
    setenv("DM_FPS_PIN", v, 1);
}

// The step in force now: what fps_pin holds (the pack's env.txt or the player's own pick). 0 = the rate adapts on its own.
JNIEXPORT jint JNICALL
Java_com_nx_android_game_Native_perfPinGet(JNIEnv *E, jclass c) {
    (void)E; (void)c;
    const char *e = getenv("DM_FPS_PIN");
    int v = e ? atoi(e) : 0;
    return v == 60 || v == 40 || v == 30 ? v : 0;   // any other value lets the rate adapt again (fps_pin's rule)
}
