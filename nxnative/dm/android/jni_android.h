// Android port: the second front end's contract (the app's :play process, jni_android.c). front_player.c
// serves the code-less NativeActivity APK (the PC flow) and the app's player alike: one shared core (window,
// input, lifecycle, settings, the host's start), two entries. The NativeActivity's callbacks feed it as always;
// the player's plain Activity has no AInputQueue, so Java hands over raw events through this shape instead —
// a key event, or a motion snapshot (every pointer's place, as AMotionEvent would report it). No AInputEvent
// crosses this line: the NativeActivity path unpacks its queue's event into a dm_java_event
// (java_event_from_input, front_player.c) so both entries drive identical handlers.
#ifndef DM_JNI_ANDROID_H
#define DM_JNI_ANDROID_H

#include <jni.h>
#include <android/native_window.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int32_t type;      // AINPUT_EVENT_TYPE_KEY or AINPUT_EVENT_TYPE_MOTION
    int32_t source;    // AINPUT_SOURCE_* (a key: the KeyEvent's source; motion: the MotionEvent's)
    int32_t device;    // the input device's id
    int32_t key_code, key_action, key_meta, key_repeat;   // a key event
    int32_t key_scan;  // the key's physical scan code (0: none): the same button has the same code on every phone
    int32_t action;    // motion: getAction() as Java carries it (the mask and the pointer index in place)
    int32_t pointers;  // motion: how many ptr[] entries hold this snapshot
    struct { int32_t id; float x, y; } ptr[16];
    float axis[10];    // AMOTION_EVENT_AXIS_X Y Z RZ LTRIGGER RTRIGGER HAT_X HAT_Y, then BRAKE, GAS (0 where the device has none)
} dm_java_event;

// the shared core, driven from the player (jni_android.c's JNI surface: Java class com.nx.android.game.Native)
int dm_java_input(const dm_java_event *e);      // one event into the game (1 consumed, as handle_event returns)
// the controllers' own table (the app's gamepad center pushes it: pad_map.h's line format) and the release of every key
// (the center mutes the input while it is open: a key the player held when it opened must not stay pressed in the game)
void dm_pad_mapping_set(const char *spec);
void dm_pad_release_all(void);
void dm_java_window_take(ANativeWindow *w);     // the app's Surface is here (the reference passes over)
void dm_java_window_drop(void);                 // it is going: the swapchain is destroyed before this returns
void dm_java_lifecycle(int ev);                 // 0 pause, 1 resume, 2 window focus, 3 window unfocus
int dm_java_start(const char *pack, const char *internal, const char *title_id, int dpi, JNIEnv *E, jobject ctx);
                                                // the pack folder, the app's internal folder, the game's title id
                                                // (saves, logs and temp go under it), the display density, the call's
                                                // JNIEnv and a Context (the Activity) for the haptics. 1 started, 0 the
                                                // save import asked to end: the game is not started

// the host's notices to the app (the player): the menu button was tapped (the app opens its drawer), the host opened one of its panels (the app
// closes its drawer, so a panel is never hidden under it), the game asks for text (the app shows the system's input method). The Activity's method
// takes the code (GameActivity.onHostNotice).
#define DM_NOTICE_MENU 1
#define DM_NOTICE_PANEL 2
#define DM_NOTICE_TEXT 3
void dm_app_notice(int what);

// The app's own answers to the host (the player front, front_player.c):
void dm_pad_prefs(int shown, int opacity);   // the gamepad's shown and opacity (the app's preferences are the only truth about them)
void dm_pad_devices(int count);              // how many controllers are in use (the app watches the system's input devices)
void dm_text_result(int ok, const char *text);   // the text the player typed (the game's thread waits for it)
char *dm_text_request_json(void);            // what the app shows: the title, the text the game starts with, the length (free() it)
void dm_app_hold(int on);                    // the app's own panel is up (the layout editor): the game waits
// The on-screen gamepad, the app's own now: the app draws it, works out what a touch hit and tells the host what the game should see.
void dm_pad_input(uint64_t buttons, int32_t lx, int32_t ly, int32_t rx, int32_t ry,
                  const int32_t *ids, const float *xs, const float *ys, int n);   // the buttons, the sticks, the touch points that are the game's
void dm_pad_layout_set(int ctl, float x, float y, float size);   // the editor's change: a control's place (fractions of the window) and its size factor
void dm_pad_layout_reset(void);              // the layout's own places and sizes again
void dm_pad_layout_save(void);               // the player's layout into the pack's settings.txt
char *dm_pad_layout_json(void);              // the layout: the controls' places and sizes (free() it)

// The front end's capabilities (the player front, front_player.c): what the host in this .so can do. The app asks right after loading it: a host built
// with an older front has no such entry at all (the call raises UnsatisfiedLinkError), and the app then says the pack has to be ported again.
#define DM_FRONT_CAPS_PLAYER 1   // the player front end: the app draws the gamepad, the host draws no panel of its own
#define DM_FRONT_CAPS_PERF 2    // the host counts the frames for the app's performance HUD (and draws no frame rate counter of its own any more)
int dm_front_caps(void);

#endif
