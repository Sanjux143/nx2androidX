// Android port: the player front end — the host's Android side for the app's :play process.
//
// What stays here and what does not: the host needs a window, a clock it can freeze, input it can turn into
// buttons, a place to keep its settings, and a way to start the game. That is this file. Everything a player
// looks at — the on-screen gamepad's drawing, the layout editor, the pause menu, the save picker, the software
// keyboard — is the app's business now (the app draws the gamepad over the game's frame and asks this file
// what a touch hit). The panels the host used to draw into the game's frame are gone: the rendering side
// (gfx_vk_present.c) asks dm_menu_state() and dm_overlay_visible(), and both say "nothing" here, so it draws
// no panel at all.
//
// The one thing still drawn by the host is the game's own software keyboard (nn::swkbd): the game asks for
// text through front_text_input() and waits; the app's own keyboard replaces that in the next step.
//
// The app talks to this file through jni_android.c (the JNI surface) and to the shared core through the
// contract in dm/host/front.h. The headless dmhost executable (adb shell) links the same file: it has no
// window, no input and no text entry, and the guards below say so.
//
// The physical controller: the keys go through the player's own table (pad_map.h), which the app pushes
// (the gamepad center): the player's binding first, the default dictionary otherwise. The face buttons
// follow their position (the Switch's layout) or their label (an Xbox controller's A is the game's A),
// per controller — the label belongs to the controller, not to the game.
#include "dm.h"
#include "front.h"
#include "dm_input.h"
#include "hle_haptics.h"
#include "overlay_layout.h"
#include "motion_map.h"
#include "pad_map.h"
#include "saves_android.h"
#include "jni_android.h"
#include <math.h>
#include <time.h>
#include <android/configuration.h>
#include <android/input.h>
#include <android/log.h>
#include <android/looper.h>
#include <android/native_window.h>
#include <android/sensor.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

void front_run(void (*guest)(void)) { guest(); }   // no window: the guest runs on the calling thread (the adb shell's dmhost)
void *front_metal_layer(void) { return NULL; }
int front_screen_max_fps(void) { return 0; }
void front_screen_match(int fps) { (void)fps; }
void dm_focus_set(int st); void dm_audio_suspend(int on); void guest_clock_freeze_for(int reason, int on); void dm_gfx_suspend(int on);
static int g_dpi;       // the display's density (the start call's argument), 0 = unknown
static ALooper *g_ilooper;   // the input thread's looper (the accelerometer's queue)
static JavaVM *g_app_vm; static jobject g_app_act;   // the app's Activity: the notices to it, and the display's rotation for the motion

// ---- the front end's capabilities: what this host can do (the app asks right after loading it) ----
int dm_front_caps(void) { return DM_FRONT_CAPS_PLAYER | DM_FRONT_CAPS_PERF; }   // the player front: the app draws the gamepad, the host draws no panel of its own; the host counts the frames for the app's HUD

// ---- the window: the app's Surface, passed over from Java (jni_android.h) ----
static pthread_mutex_t g_win_mu = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *g_win;
ANativeWindow *dm_front_window(void) { pthread_mutex_lock(&g_win_mu); ANativeWindow *w = g_win; pthread_mutex_unlock(&g_win_mu); return w; }
__attribute__((weak)) void vk_window_lost(void) {}   // the backend lets go of the window's swapchain before the window goes
static ANativeWindow *g_jwin;
void dm_java_window_take(ANativeWindow *w) {
    if (!w) return;
    ANativeWindow_acquire(w);
    pthread_mutex_lock(&g_win_mu); g_jwin = w; g_win = w; pthread_mutex_unlock(&g_win_mu);
}
void dm_java_window_drop(void) {
    pthread_mutex_lock(&g_win_mu); ANativeWindow *w = g_jwin; g_jwin = NULL; g_win = NULL; pthread_mutex_unlock(&g_win_mu);
    vk_window_lost();
    if (w) ANativeWindow_release(w);
}

// ---- the data pack: the folder the port wrote (dm_java_start's pack argument) ----
static char g_root[512];
const char *dm_front_root(void) { return g_root; }   // overlay_theme.c: the gamepad's theme comes from the pack's overlay/

// ---- settings, kept in the pack's own folder as KEY=VALUE lines ----
// The gamepad's own two values (shown, opacity) are the app's business now: the app's preferences are the only
// truth about them, the host keeps them as runtime state (dm_pad_prefs) and does not save them here.
static _Atomic int g_set_overlay = 1, g_set_rumble = 1;   // the gamepad shown (the app's); rumble and tap vibration
static _Atomic int g_set_opacity = 60;   // the gamepad's opacity, percent (OV_OPACITY_MIN..MAX) — the app's
static _Atomic int g_cust[2][2][DM_AOV_N][3], g_cust_ver;   // the player's own layout: [the editor's copy][the layout][the control][x, y, size]
static const char *const LAYOUT_NAMES[2] = {"tablet", "phone"};
static int ctl_named(const char *n) { for (int i = 0; i < OV_N; i++) if (!strcmp(OV_LAYOUTS[0][i].name, n)) return i; return -1; }
int dm_opmode(void); void dm_set_opmode(int m);   // hle_oe.c: 0 handheld, 1 docked

static void settings_load(void) {
    char p[600]; snprintf(p, sizeof p, "%s/settings.txt", g_root); FILE *f = fopen(p, "r"); char line[128];
    if (!f) return;
    while (fgets(line, sizeof line, f)) { int v; if (sscanf(line, "rumble=%d", &v) == 1) atomic_store(&g_set_rumble, v != 0);
        else if (sscanf(line, "docked=%d", &v) == 1) setenv("DM_OPMODE", v ? "1" : "0", 1);   // over env.txt's: the host reads it once, at its first query
        else if (!strncmp(line, "ui=", 3)) {   // ui=<layout>,<control>,<x>,<y>,<size>: x, y in 1/10000 of the window (-1 the layout's place), size in 1/1000
            char lay[16], name[32]; int x, y, z, l, i;
            if (sscanf(line + 3, "%15[^,],%31[^,],%d,%d,%d", lay, name, &x, &y, &z) != 5 || (i = ctl_named(name)) < 0) continue;
            for (l = 0; l < 2 && strcmp(lay, LAYOUT_NAMES[l]); l++) {}
            if (l == 2) continue;
            if (x >= 0 && x <= 10000 && y >= 0 && y <= 10000) { atomic_store(&g_cust[0][l][i][0], x + 1); atomic_store(&g_cust[0][l][i][1], y + 1); }
            if (z >= OVE_SCALE_MIN * 1000 && z <= OVE_SCALE_MAX * 1000) atomic_store(&g_cust[0][l][i][2], z);
        } else if (sscanf(line, "menu_x=%d", &v) == 1 && v >= 0 && v <= 10000) { int m = ctl_named("menu"); for (int l = 0; l < 2 && m >= 0; l++) atomic_store(&g_cust[0][l][m][0], v + 1); }   // before
        else if (sscanf(line, "menu_y=%d", &v) == 1 && v >= 0 && v <= 10000) { int m = ctl_named("menu"); for (int l = 0; l < 2 && m >= 0; l++) atomic_store(&g_cust[0][l][m][1], v + 1); }   // the editor
    }
    fclose(f);
}
static void settings_save(void) {
    char p[600], tmp[610]; snprintf(p, sizeof p, "%s/settings.txt", g_root); snprintf(tmp, sizeof tmp, "%s.new", p);
    FILE *f = fopen(tmp, "w"); if (!f) { LOG("[RED] cannot write %s", tmp); return; }
    fprintf(f, "rumble=%d\ndocked=%d\n", atomic_load(&g_set_rumble), dm_opmode() != 0);
    for (int l = 0; l < 2; l++) for (int i = 0; i < OV_N; i++) {
        _Atomic int *c = g_cust[0][l][i]; int x = atomic_load(&c[0]), y = atomic_load(&c[1]), z = atomic_load(&c[2]);
        if ((x && y) || z) fprintf(f, "ui=%s,%s,%d,%d,%d\n", LAYOUT_NAMES[l], OV_LAYOUTS[0][i].name, x && y ? x - 1 : -1, x && y ? y - 1 : -1, z ? z : 1000);
    }
    fclose(f); rename(tmp, p);
}
// The app's preferences (the gamepad's shown and opacity) reach the host here: the host keeps them as runtime state
// and the app is the only one that writes them (there is no settings.txt line for them any more).
void dm_pad_prefs(int shown, int opacity) {
    atomic_store(&g_set_overlay, shown != 0);
    atomic_store(&g_set_opacity, opacity < OV_OPACITY_MIN ? OV_OPACITY_MIN : opacity > OV_OPACITY_MAX ? OV_OPACITY_MAX : opacity);
}
void dm_haptics_android_start(JNIEnv *E, JavaVM *vm, jobject actctx, int on); void dm_haptics_android_tap(void); void dm_haptics_android_device_player(int player, int id); void dm_haptics_android_set(int on);
void dm_haptics_suspend(unsigned reason, int suspended);
__attribute__((weak)) void dm_overlay_redraw(void) {}   // the backend presents the last frame again (nothing is drawn in the player, but the calls stay)

// ---- holding the game: in the background, while a text entry is open and while the app's own panel is up (the layout editor). Its clock reads
// wait (reason 1) and its audio goes to the silent sink ----
static _Atomic int g_running, g_bg, g_tx_on, g_ui_on;
static pthread_mutex_t g_hold_mu = PTHREAD_MUTEX_INITIALIZER; static int g_held;
static void hold_sync(void) {
    pthread_mutex_lock(&g_hold_mu);
    int want = atomic_load(&g_bg) || atomic_load(&g_tx_on) || atomic_load(&g_ui_on);
    if (want != g_held) {
        g_held = want;
        if (want) { LOG("[hold] the game is held: %s", atomic_load(&g_bg) ? "the app is in the background" : atomic_load(&g_tx_on) ? "a text entry is open" : "the app's panel is open");
                    dm_movie_hold(1); dm_audio_suspend(1); guest_clock_freeze_for(1, 1); }
        else { LOG("[hold] the game runs again"); guest_clock_freeze_for(1, 0); dm_audio_suspend(0); dm_movie_hold(0); }
    }
    pthread_mutex_unlock(&g_hold_mu);
}
void dm_app_hold(int on) { atomic_store(&g_ui_on, on != 0); hold_sync(); }   // the app's panel is up (the layout editor): the game waits
static void shake_sensor(int on);   // the accelerometer, off in the background (input, below)
static void go_background(void) { if (!atomic_load(&g_running) || atomic_exchange(&g_bg, 1)) return; dm_focus_set(2); dm_focus_set(3);   // through OutOfFocus, as a game sees HOME and then the suspension
    hold_sync(); dm_gfx_suspend(1); shake_sensor(0); }
static void go_foreground(void) { if (!atomic_load(&g_running) || !atomic_exchange(&g_bg, 0)) return; dm_gfx_suspend(0); hold_sync(); dm_focus_set(2); shake_sensor(1); }
// the player's lifecycle (jni_android.h): the same dance
void dm_java_lifecycle(int ev) {
    if (ev == 0) go_background();
    else if (ev == 1) go_foreground();
    else if (atomic_load(&g_running) && !atomic_load(&g_bg)) dm_focus_set(ev == 2 ? 1 : 2);   // 2 focus, 3 unfocus
}

// ---- input: a physical controller (the app's on-screen gamepad has its own way in) ----
// The controllers' input, one set per player: player 1 is the phone (the on-screen gamepad and the phone's own
// motion go there), and a controller plays the player the table says (player 1 unless the player moved it).
static uint64_t g_btn[DM_PLAYERS]; static float g_ax[DM_PLAYERS][8];   // lx ly rx ry lt rt hatx haty
static uint64_t g_axis_btn[DM_PLAYERS];   // the buttons an analog axis carries (the triggers, the hat, the player's own axis bindings)
static pad_map_t g_map;       // the player's own bindings per controller (the app's gamepad center pushes them; pad_map.h)
static uint64_t key_bit(int device, int32_t scan, int32_t k) {
    return pad_map_key_bit(&g_map, device, scan, k);   // the player's own binding first, the default dictionary otherwise
}
static int32_t axis(float v) { return (int32_t)(v * DM_STICK_MAX); }   // the dead zone is applied per device, when the event comes in
static uint64_t g_shake_btn;   // DM_BTN_SHAKE while the device is being shaken (below); input thread only
static uint32_t g_mask = 1;    // the players with a controller (bit p = player p + 1; player 1 always)
static int g_hap_dev[DM_PLAYERS]; static uint32_t g_hap_known;   // the controller whose motor plays each player (-1: the device's own body)

// The players the controllers play: the table says which player each controller is, and a controller the table
// does not know plays player 1 (as it always did).
static uint32_t players_mask(void) {
    uint32_t m = 1;
    for (int i = 0; i < g_map.n; i++) {
        int p = g_map.devices[i].player;
        if (p > 0 && p < DM_PLAYERS) m |= 1u << p;
    }
    return m;
}

// The motor of a player: the controller that last moved it, else the table's one for that player.
static void haptics_device(int player, int device) {
    if (player < 0 || player >= DM_PLAYERS) return;
    if ((g_hap_known >> player & 1) && g_hap_dev[player] == device) return;
    g_hap_known |= 1u << player; g_hap_dev[player] = device;
    dm_haptics_android_device_player(player, device);
}

// The motors after the table changed: the controller that last moved a player keeps that player's motor (as it always
// did), and a player whose controller went away falls back to the device's own body.
static void haptics_devices(void) {
    for (int p = 0; p < DM_PLAYERS; p++) {
        int cur = g_hap_known >> p & 1 ? g_hap_dev[p] : -1, first = -1, kept = 0;
        for (int i = 0; i < g_map.n; i++) if (g_map.devices[i].player == p) {
            if (first < 0) first = g_map.devices[i].device;
            if (g_map.devices[i].device == cur) kept = 1;
        }
        haptics_device(p, kept ? cur : first);
    }
}
// The players with a controller changed: a player who left (their controller went away or was moved) keeps
// nothing pressed, and the rumble follows the players.
static void pad_players_update(void) {
    uint32_t m = players_mask();
    if (m == g_mask) return;
    uint32_t old = g_mask, gone = old & ~m;
    for (int p = 0; p < DM_PLAYERS; p++) if (gone >> p & 1) {
        g_btn[p] = 0; g_axis_btn[p] = 0; memset(g_ax[p], 0, sizeof g_ax[p]);
        LOG("[input] player %d's controller went away: its keys are released", p + 1);
    }
    g_mask = m;
    // the rumble follows the players: a player with a controller has a motor (one without falls back to the device's body)
    for (int p = 0; p < DM_PLAYERS; p++) {
        int want = (p == 0 || (m >> p & 1)) ? 1 : 0, have = (old >> p & 1) ? 1 : 0;
        if (want != have) {
            dm_haptics_route(p, want);
            LOG("[input] player %d's rumble: %s", p + 1, want ? "its own controller" : "the device's body");
        }
    }
    LOG("[input] the players with a controller: %d", __builtin_popcount(m));
}
static void pad_publish(void) {
    pad_players_update();   // the table may have changed since the last event (a controller came or went)
    for (int p = 0; p < DM_PLAYERS; p++) {
        uint64_t b = g_btn[p] | g_axis_btn[p] | (p == 0 ? g_shake_btn : 0);   // the phone's shake is player 1's
        dm_pad_set_player(p, b, axis(g_ax[p][0]), -axis(g_ax[p][1]), axis(g_ax[p][2]), -axis(g_ax[p][3]));   // Android's y grows downward, the Switch's upward
    }
    dm_pad_players(g_mask);
}

// The player's own table for the controllers (the app's gamepad center pushes it: pad_map.h's line format).
void dm_pad_mapping_set(const char *spec) {
    int n = pad_map_parse(&g_map, spec);
    haptics_devices();   // the motors follow the table: a controller that went away leaves its player to the device's body
    LOG("[input] the controllers' table: %d device(s)", n);
    for (int i = 0; i < g_map.n; i++) {
        int p = g_map.devices[i].player;
        LOG("[input]   the input device %d plays %s", g_map.devices[i].device, p < 0 ? "nobody (it sits out)" : p == 0 ? "player 1" : "another player");
    }
    // the table changed: a controller that went away leaves its player with nothing pressed (the keys it held
    // are released now, not at the next key event)
    pad_publish();
}

// Everything the controller holds is released: the gamepad center mutes the input while it is open, so a key the
// player held when it opened (and released while it was open) must not stay pressed in the game.
void dm_pad_release_all(void) {
    for (int p = 0; p < DM_PLAYERS; p++) { g_btn[p] = 0; g_axis_btn[p] = 0; memset(g_ax[p], 0, sizeof g_ax[p]); }
    for (int p = 0; p < DM_PLAYERS; p++) dm_pad_set_player(p, 0, 0, 0, 0, 0);
    LOG("[input] the controller's keys are all released");
}

// ---- the on-screen gamepad: the app owns it now ----
// The app draws the gamepad, works out what a touch hit and tells the host what the game should see
// (dm_pad_input: the buttons, the sticks and the touch points that are the game's). The host keeps the layout
// (the controls' places and sizes, the player's own changes) and hands it to the app (dm_pad_layout_json).
static _Atomic int g_pads;          // the controllers in use (the app counts them, dm_pad_devices): while there is one, the app hides the gamepad
static _Atomic int g_pad_in = -1;   // the device that last sent a controller's input (the haptics' motor belongs to it)
int dm_overlay_visible(void) { return 0; }   // the render side: the app draws the gamepad in the player, so the host draws none of it
int dm_overlay_layout(void) {   // the phone's layout in a window at least OV_PHONE_ASPECT wide for its height (overlay_layout.h)
    static int last_w = -1, last_h = -1, phone;
    ANativeWindow *w = dm_front_window(); int ww = w ? ANativeWindow_getWidth(w) : 0, wh = w ? ANativeWindow_getHeight(w) : 0;
    if (ww != last_w || wh != last_h) { last_w = ww; last_h = wh; phone = wh > 0 && (float)ww / (float)wh >= OV_PHONE_ASPECT;
        if (wh > 0) LOG("[overlay] window %dx%d: the %s layout", ww, wh, phone ? "phone" : "tablet"); }
    return phone;
}
int dm_overlay_custom(int layout, int ctl, float *x, float *y, float *s) {
    if (layout < 0 || layout > 1 || ctl < 0 || ctl >= OV_N) return 0;
    _Atomic int *c = g_cust[0][layout][ctl]; int cx = atomic_load(&c[0]), cy = atomic_load(&c[1]), z = atomic_load(&c[2]);
    *s = z ? z / 1000.0f : 1;
    if (!cx || !cy) return 0;
    *x = (cx - 1) / 10000.0f; *y = (cy - 1) / 10000.0f; return 1;
}
int dm_overlay_custom_ver(void) { return atomic_load(&g_cust_ver); }
float dm_overlay_opacity(void) { return atomic_load(&g_set_opacity) / 100.0f; }
static void cust_place(int set, int l, int i, float x, float y) { atomic_store(&g_cust[set][l][i][0], 1 + (int)lroundf(x * 10000)); atomic_store(&g_cust[set][l][i][1], 1 + (int)lroundf(y * 10000)); }
float dm_overlay_scale(void) {
    static int last_w = -1, last_h = -1; static float s = 1;
    ANativeWindow *w = dm_front_window(); int ww = w ? ANativeWindow_getWidth(w) : 0, h = w ? ANativeWindow_getHeight(w) : 0;
    if (h != last_h || ww != last_w) { last_w = ww; last_h = h; s = 1;   // (the width picks the layout, whose buttons are sized for its form factor)
        float face = 0.1f; const ov_ctl *L = OV_CTLS; for (int i = 0; i < OV_N; i++) if (L[i].kind == OV_BUTTON && L[i].btn == DM_BTN_A) face = L[i].w;   // A's width, in window heights
        if (h > 0 && g_dpi > 0 && g_dpi < 0xfffe) { float mm = face * (float)h / (float)g_dpi * 25.4f; s = OV_MIN_MM / mm; if (s < 1) s = 1; if (s > 1.45f) s = 1.45f; }   // (more and the face buttons touch)
        LOG("[overlay] window %dx%d, density %d dpi: face buttons %.1f mm, controls x%.2f", ww, h, g_dpi, h > 0 && g_dpi > 0 && g_dpi < 0xfffe ? face * h / g_dpi * 25.4f : 0, s); }
    return s;
}
__attribute__((weak)) void dm_picture_rect(float r[4]);   // gfx_vk_present.c: the picture inside the window
// The layout editor's changes (the app's editor): a control's place (fractions of the window) and its size factor. The host keeps them// (g_cust) and hands them back in the layout; the app writes them when the player saves, and the host writes them into settings.txt.
void dm_pad_layout_set(int ctl, float x, float y, float size) {
    int l = dm_overlay_layout(); if (ctl < 0 || ctl >= OV_N) return;
    if (x >= 0 && x <= 1 && y >= 0 && y <= 1) cust_place(0, l, ctl, x, y);
    if (size >= OVE_SCALE_MIN && size <= OVE_SCALE_MAX) atomic_store(&g_cust[0][l][ctl][2], (int)lroundf(size * 1000));
    atomic_fetch_add(&g_cust_ver, 1);
}
void dm_pad_layout_reset(void) {   // the layout's own places and sizes again
    int l = dm_overlay_layout();
    for (int i = 0; i < OV_N; i++) { atomic_store(&g_cust[0][l][i][0], 0); atomic_store(&g_cust[0][l][i][1], 0); atomic_store(&g_cust[0][l][i][2], 0); }
    atomic_fetch_add(&g_cust_ver, 1);
}
void dm_pad_layout_save(void) { settings_save(); }
// What the app routes: the buttons held, the two sticks and the touch points that are the game's (window pixels; the host puts them
// into the game's picture). The app worked out what every touch hit (its own hit test, the same numbers the host used).
void dm_pad_input(uint64_t buttons, int32_t lx, int32_t ly, int32_t rx, int32_t ry, const int32_t *ids, const float *xs, const float *ys, int n) {
    dm_input_set(buttons, lx, ly, rx, ry);
    dm_touch t[16]; int m = 0;
    ANativeWindow *w = dm_front_window(); float W = w ? (float)ANativeWindow_getWidth(w) : 1, H = w ? (float)ANativeWindow_getHeight(w) : 1;
    float pic[4] = {0, 0, 1, 1}; if (dm_picture_rect) dm_picture_rect(pic); if (pic[2] <= 0 || pic[3] <= 0) pic[2] = pic[3] = 1;   // gfx_vk_present.c: the picture inside the window
    for (int i = 0; i < n && m < 16 && ids && xs && ys; i++) {
        t[m].id = (uint32_t)(ids[i] & 15);
        t[m].x = (xs[i] / W - pic[0]) / pic[2];
        t[m].y = (ys[i] / H - pic[1]) / pic[3];
        m++;
    }
    dm_touch_set(t, m);
}

// ---- controllers: the app watches the system's input devices and says how many are in use ----
// The host used to poll InputDevice's list once a second. The app does it now (it has the system's listener):
// it counts a device that is a gamepad or a joystick, is not virtual, and has sent input — Xiaomi phones keep a
// system device "uinput-xiaomi" attached for good that says it is a gamepad and sends nothing, and counting it
// hid the gamepad and its menu button (issue #22). While a controller is in use the app hides the gamepad and
// every touch is the game's; the host only needs the count (dm_pad_devices, jni_android.h).
void dm_pad_devices(int count) {
    int was = atomic_exchange(&g_pads, count);
    if (was && !count) LOG("[input] no controller in use: the gamepad is shown");
    else if (!was && count) LOG("[input] a controller is in use: the gamepad steps aside");
}

// ---- the app's pad data: the layout it draws and the state it shows ----
// The layout: the active layout's controls (with the player's own places and sizes), the version, the window's size, the scale and the
// opacity. Every measure is in window heights (the layout's own unit), so the app multiplies them by its own height and works in the
// same numbers the host's hit test used: the centre, the half sizes, the corners' radius and how far outside its edge a control still
// takes a touch (the reach). The size factor is the player's own (1 = the layout's), so the app can scale a control from it.
static const char *pad_kind_name(int kind) { return kind == OV_STICK_L || kind == OV_STICK_R ? "stick" : kind == OV_DPAD ? "dpad" : kind == OV_MENU ? "menu" : "button"; }
char *dm_pad_layout_json(void) {
    ANativeWindow *w = dm_front_window(); float W = w ? (float)ANativeWindow_getWidth(w) : 1, H = w ? (float)ANativeWindow_getHeight(w) : 1;
    int L = dm_overlay_layout();
    size_t cap = 512 + (size_t)DM_AOV_N * 200; char *out = malloc(cap); if (!out) return NULL;
    size_t o = (size_t)snprintf(out, cap, "{\"layout\":\"%s\",\"ver\":%d,\"opacity\":%d,\"w\":%.0f,\"h\":%.0f,\"scale\":%.5f,\"controls\":[",
                                LAYOUT_NAMES[L], atomic_load(&g_cust_ver), atomic_load(&g_set_opacity), (double)W, (double)H, (double)dm_overlay_scale());
    for (int i = 0; i < DM_AOV_N; i++) {
        const ov_ctl *c = &OV_CTLS[i]; float b[4]; ov_box(c, W, H, b);   // b: centre x, y and HALF width, height, in window px — the app draws the same boxes the hit test uses
        float cx = b[0] / H, cy = b[1] / H, hw = b[2] / H, hh = b[3] / H, reach = ov_reach(c, b, H) / H;
        float x, y, s = 1; dm_overlay_custom(L, i, &x, &y, &s);
        o += (size_t)snprintf(out + o, cap - o, "%s{\"n\":\"%s\",\"k\":\"%s\",\"b\":%llu,\"cx\":%.5f,\"cy\":%.5f,\"hw\":%.5f,\"hh\":%.5f,\"r\":%.5f,\"reach\":%.5f,\"s\":%.4f}",
                              i ? "," : "", c->name, pad_kind_name(c->kind), (unsigned long long)c->btn,
                              (double)cx, (double)cy, (double)hw, (double)hh, (double)(ov_corner(c, b) / H), (double)reach, (double)s);
    }
    snprintf(out + o, cap - o, "]}");
    return out;
}

// ---- the drawer's rows: the host's own settings, driven from the app ----
// The state lives in the g_set_* atomics and the save manager; the drawer reads and sets them through these.
// The gamepad's own two values (shown, opacity) are the app's: they are runtime state only, and the app pushes
// them with dm_pad_prefs; the rest (rumble, handheld or docked) is saved in settings.txt.
static _Atomic int g_sv_st, g_sv_n;   // the export's outcome (OVX_*), the exports made
int dm_menu_get(int what) {
    switch (what) {
    case 0: return atomic_load(&g_set_overlay);
    case 1: return atomic_load(&g_set_opacity);
    case 2: return atomic_load(&g_set_rumble);
    case 4: return dm_opmode() != 0;
    }
    return 0;
}
void dm_menu_overlay_set(int on) { atomic_store(&g_set_overlay, on != 0); }
int dm_menu_overlay_get(void) { return atomic_load(&g_set_overlay); }
void dm_menu_opacity_set(int v) { int c = v < OV_OPACITY_MIN ? OV_OPACITY_MIN : v > OV_OPACITY_MAX ? OV_OPACITY_MAX : v; atomic_store(&g_set_opacity, c); }
int dm_menu_opacity_get(void) { return atomic_load(&g_set_opacity); }
void dm_menu_rumble_set(int on) { atomic_store(&g_set_rumble, on != 0); dm_haptics_android_set(on); settings_save(); }
int dm_menu_rumble_get(void) { return atomic_load(&g_set_rumble); }
void dm_menu_mode_set(int docked) { dm_set_opmode(docked ? 1 : 0); settings_save(); }
int dm_menu_mode_get(void) { return dm_opmode() != 0; }
void dm_menu_edit_open(void) { LOG("[RED] [overlay] the layout editor is the app's now: the host has no editor to open"); }
int dm_menu_state(void) { return -1; }   // the host has no panel of its own: the app's drawer is the only one

// ---- the save manager (saves_android.c): the export runs on a thread of its own; the drawer shows its outcome ----
static pthread_mutex_t g_sp_mu = PTHREAD_MUTEX_INITIALIZER;   // the export's note
static char g_sv_note[96];
int dm_save_note(char *out, int cap) { pthread_mutex_lock(&g_sp_mu); snprintf(out, (size_t)cap, "%s", g_sv_note); pthread_mutex_unlock(&g_sp_mu); return atomic_load(&g_sv_st); }
static void *export_run(void *a) {
    (void)a; char name[64], err[600]; int r = dm_saves_export("", name, sizeof name, err, sizeof err);
    pthread_mutex_lock(&g_sp_mu); snprintf(g_sv_note, sizeof g_sv_note, "%s", r ? err : name); pthread_mutex_unlock(&g_sp_mu);
    atomic_store(&g_sv_st, !r ? OVX_DONE : r == -ENOENT ? OVX_NOSAVE : OVX_FAILED); atomic_fetch_add(&g_sv_n, 1);
    return NULL;
}
void dm_menu_export(void) { pthread_t t; atomic_store(&g_sv_st, OVX_BUSY); if (pthread_create(&t, NULL, export_run, NULL)) { atomic_store(&g_sv_st, OVX_FAILED); LOG("[RED] [saves] cannot start the export"); } else pthread_detach(t); }
char *dm_menu_saves(void) {   // the export folder's saves as a JSON array (the drawer's list); free() it
    dm_save_entry e[64]; int n = dm_saves_list(e, 64);
    size_t cap = 64 + (size_t)(n > 0 ? n : 0) * 96; char *out = malloc(cap); if (!out) return NULL;
    size_t o = (size_t)snprintf(out, cap, "[");
    for (int i = 0; i < n; i++) o += (size_t)snprintf(out + o, cap - o, "%s\"%s\"", i ? "," : "", e[i].name);
    snprintf(out + o, cap - o, "]");
    return out;
}
int dm_menu_import(const char *name) { return name && *name ? dm_saves_request_import(name) : 0; }   // 1: the import is set (the app ends; it happens at the next start)
int dm_menu_export_note(char *out, int cap) { return dm_save_note(out, cap); }

// ---- text entry: the game's software keyboard (nn::swkbd, hle_swkbd.c → front_text_input) ----
// The game asks for text and waits. The host holds the game (its clock reads wait, its audio goes to the silent sink)
// and asks the app: the app shows the system's own input method over the game and hands the answer back
// (Native.textResult), and the game goes on. The host draws nothing here — no keyboard over the game's picture.
// The request (the title, the text the game starts with, how many characters it takes) is read by the app from
// Native.textRequest(); the answer comes back through dm_text_result (jni_android.h).
static pthread_mutex_t g_tx_mu = PTHREAD_MUTEX_INITIALIZER; static pthread_cond_t g_tx_cv = PTHREAD_COND_INITIALIZER;
static struct { char title[128], text[256]; int max, done, ok; } g_tx;
void dm_text_result(int ok, const char *text) {   // the app's answer (the game's thread waits for it)
    pthread_mutex_lock(&g_tx_mu);
    if (!g_tx.done) { g_tx.ok = ok != 0; if (ok && text) snprintf(g_tx.text, sizeof g_tx.text, "%s", text); g_tx.done = 1; }
    pthread_cond_broadcast(&g_tx_cv);
    pthread_mutex_unlock(&g_tx_mu);
}
static void json_str(char *out, size_t cap, const char *s);   // a JSON string's contents: the quotes and the backslashes escaped
char *dm_text_request_json(void) {   // what the app shows: the title, the text the game starts with, the length
    pthread_mutex_lock(&g_tx_mu);
    size_t cap = sizeof g_tx.title + sizeof g_tx.text + 64; char *out = malloc(cap);
    if (out) {
        char title[160], text[300];
        json_str(title, sizeof title, g_tx.title); json_str(text, sizeof text, g_tx.text);
        snprintf(out, cap, "{\"title\":\"%s\",\"text\":\"%s\",\"max\":%d}", title, text, g_tx.max);
    }
    pthread_mutex_unlock(&g_tx_mu);
    return out;
}
static void json_str(char *out, size_t cap, const char *s) {   // a JSON string's contents: the quotes and the backslashes escaped
    size_t o = 0;
    for (const unsigned char *p = (const unsigned char *)s; *p && o + 7 < cap; p++) {
        if (*p == '"' || *p == '\\') { out[o++] = '\\'; out[o++] = (char)*p; }
        else if (*p < 32) o += (size_t)snprintf(out + o, cap - o, "\\u%04x", *p);
        else out[o++] = (char)*p;
    }
    out[o] = 0;
}
int front_text_input(const char *title, const char *sub, const char *guide, const char *initial, int max_chars, char *out, int out_cap) {
    if (!out || out_cap <= 0) return -1;
    if (!dm_front_window()) return -1;   // the adb shell executable, or no window yet: nothing is shown
    char title_buf[128];
    pthread_mutex_lock(&g_tx_mu);
    const char *t = title && *title ? title : guide && *guide ? guide : sub && *sub ? sub : "Enter text";
    snprintf(title_buf, sizeof title_buf, "%s", t);
    snprintf(g_tx.title, sizeof g_tx.title, "%s", title_buf);
    g_tx.text[0] = 0;
    {   // the text the game starts with (ASCII only, as the overlay's font had it)
        int n = 0;
        for (const char *s = initial ? initial : ""; *s && n < (int)sizeof g_tx.text - 1; s++)
            if ((unsigned char)*s >= 32 && (unsigned char)*s < 127) g_tx.text[n++] = *s;
        g_tx.text[n] = 0;
    }
    g_tx.max = max_chars > 0 && max_chars < (int)sizeof g_tx.text - 1 ? max_chars : (int)sizeof g_tx.text - 1;
    if (g_tx.max > out_cap - 1) g_tx.max = out_cap - 1;
    g_tx.done = g_tx.ok = 0;
    pthread_mutex_unlock(&g_tx_mu);
    dm_input_set(0, 0, 0, 0, 0); dm_touch_set(NULL, 0);   // nothing stays pressed in the game
    atomic_store(&g_tx_on, 1); hold_sync();
    LOG("[text] entry open: \"%s\" (at most %d characters)", title_buf, g_tx.max);
    dm_app_notice(DM_NOTICE_TEXT);   // the app: its own input method over the game
    pthread_mutex_lock(&g_tx_mu);
    while (!g_tx.done) pthread_cond_wait(&g_tx_cv, &g_tx_mu);
    int ok = g_tx.ok; if (ok) snprintf(out, (size_t)out_cap, "%s", g_tx.text);
    pthread_mutex_unlock(&g_tx_mu);
    atomic_store(&g_tx_on, 0); hold_sync();
    LOG("[text] entry %s", ok ? "accepted" : "cancelled");
    return ok;
}

// ---- input: the keys and the controllers ----
// The touches are the app's now: it works out what every touch hit (its own hit test) and tells the host what the game should see
// (dm_pad_input). The host only takes the physical controller's input.
__attribute__((weak)) void dm_picture_rect(float r[4]);   // gfx_vk_present.c
static int handle_event(const dm_java_event *e) {
    int32_t src = e->source, type = e->type;
    if (type == AINPUT_EVENT_TYPE_KEY) {
        if (atomic_load(&g_tx_on)) return 1;   // the text entry is up: the app's input method has the keys
        int32_t k = e->key_code, down = e->key_action == AKEY_EVENT_ACTION_DOWN;
        if (k == AKEYCODE_BACK || k == AKEYCODE_BUTTON_MODE || k == AKEYCODE_MENU) {   // the menu: the app's drawer (the app answers Back itself)
            if (down && e->key_repeat == 0) dm_app_notice(DM_NOTICE_MENU);
            return 1;
        }
        int p = pad_map_player(&g_map, e->device);   // the player this controller plays (-1: set aside)
        uint64_t bit = key_bit(e->device, e->key_scan, k);
        if (pad_log_keys()) LOG("[input] device %d (player %d): scan %d key %d -> %s", e->device, p + 1, e->key_scan, k, pad_button_name(bit));
        if (!bit) {   // the rest: the system's
            static uint8_t seen[64];
            if ((src & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD && down && k >= 0 && k < 512 && !(seen[k >> 3] >> (k & 7) & 1)) {
                seen[k >> 3] |= (uint8_t)(1 << (k & 7)); LOG("[input] controller key %d (device %d): not mapped", k, e->device);
            }
            return 0;
        }
        atomic_store(&g_pad_in, e->device);   // a controller is in use: the app hides the gamepad
        if (p < 0) return 1;
        haptics_device(p, e->device);   // this controller's motor is this player's
        if (down) g_btn[p] |= bit; else if (e->key_action == AKEY_EVENT_ACTION_UP) g_btn[p] &= ~bit;
        pad_publish(); return 1;
    }
    if (atomic_load(&g_tx_on) && ((src & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK || (src & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD)) return 1;   // sticks: not for the text entry
    if ((src & AINPUT_SOURCE_JOYSTICK) == AINPUT_SOURCE_JOYSTICK || (src & AINPUT_SOURCE_GAMEPAD) == AINPUT_SOURCE_GAMEPAD) {
        atomic_store(&g_pad_in, e->device);
        int p = pad_map_player(&g_map, e->device);   // the player this controller plays (-1: set aside)
        if (p < 0) return 1;
        haptics_device(p, e->device);   // this controller's motor is this player's
        float *ax = g_ax[p];
        float dz = pad_map_deadzone(&g_map, e->device);   // the stick's dead zone: the device's own (the default when it has none)
        for (int i = 0; i < 4; i++) { float v = e->axis[i]; ax[i] = (v > -dz && v < dz) ? 0 : v; }
        ax[4] = e->axis[4]; ax[5] = e->axis[5];
        ax[6] = e->axis[6]; ax[7] = e->axis[7];
        if (ax[4] == 0) ax[4] = e->axis[8]; if (ax[5] == 0) ax[5] = e->axis[9];
        // the analog buttons: the triggers (the device's threshold), the hat and the player's own axis bindings.
        // A pad may report its triggers as LTRIGGER/RTRIGGER or as BRAKE/GAS (the event's array puts BRAKE at 8 and GAS at 9),
        // so both ids are looked up for each of the two trigger slots.
        float tr = pad_map_trigger(&g_map, e->device);
        uint64_t ab = 0;
        if (!pad_map_has_axis(&g_map, e->device, PAD_AXIS_LTRIGGER) && !pad_map_has_axis(&g_map, e->device, PAD_AXIS_BRAKE) && ax[4] > tr) ab |= DM_BTN_ZL;
        if (!pad_map_has_axis(&g_map, e->device, PAD_AXIS_RTRIGGER) && !pad_map_has_axis(&g_map, e->device, PAD_AXIS_GAS) && ax[5] > tr) ab |= DM_BTN_ZR;
        if (!pad_map_has_axis(&g_map, e->device, PAD_AXIS_HAT_X)) { if (ax[6] < -0.5f) ab |= DM_BTN_LEFT; if (ax[6] > 0.5f) ab |= DM_BTN_RIGHT; }
        if (!pad_map_has_axis(&g_map, e->device, PAD_AXIS_HAT_Y)) { if (ax[7] < -0.5f) ab |= DM_BTN_UP; if (ax[7] > 0.5f) ab |= DM_BTN_DOWN; }
        ab |= pad_map_axis_bit(&g_map, e->device, PAD_AXIS_LTRIGGER, ax[4]);
        ab |= pad_map_axis_bit(&g_map, e->device, PAD_AXIS_BRAKE, ax[4]);
        ab |= pad_map_axis_bit(&g_map, e->device, PAD_AXIS_RTRIGGER, ax[5]);
        ab |= pad_map_axis_bit(&g_map, e->device, PAD_AXIS_GAS, ax[5]);
        ab |= pad_map_axis_bit(&g_map, e->device, PAD_AXIS_HAT_X, ax[6]);
        ab |= pad_map_axis_bit(&g_map, e->device, PAD_AXIS_HAT_Y, ax[7]);
        g_axis_btn[p] = ab;
        pad_publish(); return 1;
    }
    if ((src & AINPUT_SOURCE_TOUCHSCREEN) == AINPUT_SOURCE_TOUCHSCREEN) return 1;   // the touches are the app's (it routes them)
    return 0;
}
int dm_java_input(const dm_java_event *e) { return handle_event(e); }

// ---- the device's motion: the controller's six-axis sensor turns as the device does (dm_motion_set, hle_hid.c), as the Joy-Cons turn with the console in
// handheld mode (The Legend of Zelda: Tears of the Kingdom aims the bow by them). The gyroscope at 200 Hz (the Switch sensor's rate) with the latest accelerometer
// sample, in the screen's frame (the display's rotation is read through JNI every 250 ms: a 180-degree flip in sensorLandscape sends no configuration change),
// angular velocity in revolutions / s and gravity's direction in G (the opposite sign of Android's). DM_MOTION=0 turns it off, DM_MOTION_RAW=1 keeps the measured
// direction of a flick instead of the made-up waveform, DM_MOTION_LOG=1 logs the mapped values once a second. The switches are read again while the game runs, so
// the app's ctl file (the drawer's live switches) turns the motion on and off without restarting the game. Off in the background with the accelerometer
// ---- shaking the device: the controller is shaken (DM_BTN_SHAKE: its six-axis sensor, hle_hid.c; Super Mario Odyssey's cap throw), as the iOS front end does with the phone ----
// The accelerometer at 60 Hz on the input thread's looper (no permission needed): gravity is followed by a slow low-pass (about 0.8 s), and a sample whose acceleration beyond it
// exceeds DM_SHAKE_G (1.5 G by default; env.txt overrides) is a hit. The controller is shaken until 120 ms after the last hit: a flick is one shake (the host finishes it as a whole
// cycle) and shaking on keeps it going. It goes through player 1's controller path, so it adds to the on-screen gamepad and a controller alike. Off while the app is in the background
static ASensorEventQueue *g_sq; static const ASensor *g_accel, *g_gyro;
static _Atomic int g_motion_on = 1;   // the device's motion drives the controller's six-axis sensor
static _Atomic int g_motion_raw;      // keep the measured direction of a flick: the game's own gestures read it, the made-up waveform stops
static _Atomic int g_motion_log;      // log the mapped values once a second (DM_MOTION_LOG=1)
static int64_t g_mo_next;             // the next read of the switches (the app's ctl file changes them while the game runs)
// the display's rotation, read through JNI (the Activity's window): 0..3 = 0, 90, 180, 270 degrees
static JNIEnv *g_menv; static jobject g_display; static jmethodID g_mRotation; static int g_rot;
static int jexc(JNIEnv *E) { if (!(*E)->ExceptionCheck(E)) return 0; (*E)->ExceptionClear(E); return 1; }
static void rotation_start(void) {
    JavaVM *vm = g_app_vm; JNIEnv *E;
    if (!vm || !g_app_act || (*vm)->AttachCurrentThread(vm, &E, NULL) != JNI_OK) { LOG("[RED] front end: the input thread cannot reach the Activity: motion keeps rotation 0"); return; }
    jclass cA = (*E)->GetObjectClass(E, g_app_act);
    jmethodID mWm = cA ? (*E)->GetMethodID(E, cA, "getWindowManager", "()Landroid/view/WindowManager;") : NULL;
    jobject wm = mWm ? (*E)->CallObjectMethod(E, g_app_act, mWm) : NULL; if (jexc(E) || !wm) { LOG("[RED] front end: no WindowManager: motion keeps rotation 0"); return; }
    jclass cWm = (*E)->FindClass(E, "android/view/WindowManager"), cD = (*E)->FindClass(E, "android/view/Display");
    jmethodID mDisp = cWm ? (*E)->GetMethodID(E, cWm, "getDefaultDisplay", "()Landroid/view/Display;") : NULL; if (jexc(E) || !mDisp || !cD) return;
    jobject d = (*E)->CallObjectMethod(E, wm, mDisp); g_mRotation = (*E)->GetMethodID(E, cD, "getRotation", "()I"); if (jexc(E) || !d || !g_mRotation) return;
    g_display = (*E)->NewGlobalRef(E, d); g_menv = E;
}
static void rotation_poll(int64_t now) {
    static int64_t next; static int first = 1;
    if (!g_menv || !g_display || now < next) return;
    next = now + 250000000;
    int r = (*g_menv)->CallIntMethod(g_menv, g_display, g_mRotation); if (jexc(g_menv)) return;
    if ((r & 3) != g_rot || first) LOG("front end: display rotation %d degrees: motion axes follow it", (r & 3) * 90);
    g_rot = r & 3; first = 0;
}
// a gyroscope sample with the latest acceleration (the device's frame, m/s^2) → the host
static void motion_feed(const ASensorEvent *e, const double acc[3]) {
    double g[3] = {e->vector.x, e->vector.y, e->vector.z}, a[3] = {acc[0], acc[1], acc[2]}, gm[3], am[3];
    rotation_poll(e->timestamp);
    dm_motion_map(g_rot, g, gm); dm_motion_map(g_rot, a, am);
    float fa[3], fg[3];
    for (int i = 0; i < 3; i++) { fg[i] = (float)(gm[i] / (2 * M_PI)); fa[i] = (float)(-am[i] / ASENSOR_STANDARD_GRAVITY); }
    dm_motion_set(fa, fg, e->timestamp);
    static int64_t logged;
    if (atomic_load(&g_motion_log) && e->timestamp - logged >= 1000000000) { logged = e->timestamp;
        LOG("front end: motion (rotation %d) acc %+.2f %+.2f %+.2f G, gyro %+.3f %+.3f %+.3f rev/s", g_rot * 90, fa[0], fa[1], fa[2], fg[0], fg[1], fg[2]); }
}
static void shake_sensor(int on) {
    if (!g_sq || !g_accel) return;
    // the rate goes with the registration: enableSensor + setEventRate left it at the default 200 ms on the Y700 (dumpsys sensorservice), too slow for a flick.
    // 200 Hz with the gyroscope (its samples carry the latest acceleration), 60 Hz for shaking alone
    int gyro = on && atomic_load(&g_motion_on) && g_gyro;
    if (on) ASensorEventQueue_registerSensor(g_sq, g_accel, gyro ? 5000 : 16667, 0); else ASensorEventQueue_disableSensor(g_sq, g_accel);
    if (g_gyro) { if (gyro) ASensorEventQueue_registerSensor(g_sq, g_gyro, 5000, 0); else ASensorEventQueue_disableSensor(g_sq, g_gyro); }
}
// the gyroscope, looked up when the player wants the motion (it may be off at the start and turned on later)
static void motion_gyro_find(void) {
    if (g_gyro) return;
    ASensorManager *m = ASensorManager_getInstanceForPackage(NULL);
    g_gyro = m ? ASensorManager_getDefaultSensor(m, ASENSOR_TYPE_GYROSCOPE) : NULL;
    if (g_gyro) { rotation_start(); LOG("front end: the device's gyroscope (%s) moves the controller's six-axis sensor", ASensor_getName(g_gyro)); }
    else LOG("front end: no gyroscope: the device's motion is not available");
}
// the switches, read again while the game runs: the app's ctl file (DM_CTL) writes them, so the change takes effect at once
static void motion_poll(int64_t now) {
    if (now < g_mo_next) return;
    g_mo_next = now + 250000000;
    const char *e = getenv("DM_MOTION");
    int on = !(e && atoi(e) == 0);
    if (on != atomic_load(&g_motion_on)) {
        atomic_store(&g_motion_on, on);
        if (on) motion_gyro_find();
        LOG("front end: the device's motion is %s", on ? "on" : "off");
        shake_sensor(!atomic_load(&g_bg));
    }
    const char *r = getenv("DM_MOTION_RAW");
    atomic_store(&g_motion_raw, r && atoi(r) > 0);
    const char *lg = getenv("DM_MOTION_LOG");
    atomic_store(&g_motion_log, lg && atoi(lg) > 0);
}
static int on_sensor(int fd, int events, void *data) {
    (void)fd; (void)events; (void)data;
    static double thr, gx, gy, gz, peak; static int64_t last = -1; static int init;
    static double acc[3] = {0, 0, ASENSOR_STANDARD_GRAVITY};
    if (!thr) { const char *e = getenv("DM_SHAKE_G"); thr = e && atof(e) > 0 ? atof(e) : 1.5; }
    ASensorEvent ev[16]; ssize_t n;
    while ((n = ASensorEventQueue_getEvents(g_sq, ev, 16)) > 0) for (ssize_t i = 0; i < n; i++) {
        if (ev[i].type == ASENSOR_TYPE_GYROSCOPE) { motion_feed(&ev[i], acc); continue; }
        if (ev[i].type != ASENSOR_TYPE_ACCELEROMETER) continue;
        motion_poll(ev[i].timestamp);
        acc[0] = ev[i].acceleration.x; acc[1] = ev[i].acceleration.y; acc[2] = ev[i].acceleration.z;
        // the raw mode: the game's own gestures read the measured direction, so the made-up shake stops (without a gyroscope it stays)
        if (g_gyro && atomic_load(&g_motion_raw)) continue;
        double x = ev[i].acceleration.x / ASENSOR_STANDARD_GRAVITY, y = ev[i].acceleration.y / ASENSOR_STANDARD_GRAVITY, z = ev[i].acceleration.z / ASENSOR_STANDARD_GRAVITY;
        if (!init) { gx = x; gy = y; gz = z; init = 1; }
        double dx = x - gx, dy = y - gy, dz = z - gz, g = sqrt(dx * dx + dy * dy + dz * dz);
        gx += (x - gx) * 0.02; gy += (y - gy) * 0.02; gz += (z - gz) * 0.02;
        if (g > thr) { if (last < 0) peak = 0; last = ev[i].timestamp; peak = fmax(peak, g); }
        int on = last >= 0 && ev[i].timestamp - last < 120000000;
        if (!on && last >= 0) { LOG("front end: device shaken (peak %.1f G)", peak); last = -1; }
        uint64_t want = on ? DM_BTN_SHAKE : 0;
        if (want != g_shake_btn) { g_shake_btn = want; pad_publish(); }
    }
    return 1;
}
static void shake_start(void) {
    ASensorManager *m = ASensorManager_getInstanceForPackage(NULL);
    g_accel = m ? ASensorManager_getDefaultSensor(m, ASENSOR_TYPE_ACCELEROMETER) : NULL;
    if (!g_accel) { LOG("front end: no accelerometer, shaking the device does nothing"); return; }
    g_sq = ASensorManager_createEventQueue(m, g_ilooper, 2, on_sensor, NULL);
    const char *e = getenv("DM_SHAKE_G");
    LOG("front end: shaking the device shakes the controller (threshold %.2f G)", e && atof(e) > 0 ? atof(e) : 1.5);
    const char *mo = getenv("DM_MOTION");
    if (mo && !atoi(mo)) { atomic_store(&g_motion_on, 0); LOG("front end: DM_MOTION=0: the device's motion is off"); }
    else motion_gyro_find();
    shake_sensor(!atomic_load(&g_bg));
}
static void *input_thread(void *a) {
    (void)a; crash_thread_init(); g_ilooper = ALooper_prepare(0);
    shake_start();
    for (;;) ALooper_pollOnce(-1, NULL, NULL, NULL);
    return NULL;
}

// ---- the host ----
int main(int argc, char **argv);   // dm/host/main.c
static void *host_thread(void *a) {
    (void)a; crash_thread_init();
    char mods[600]; snprintf(mods, sizeof mods, "%s/mods", g_root);
    char *argv[] = {"dmhost", mods, NULL};
    atomic_store(&g_running, 1);
    int rc = main(2, argv);
    LOG("host returned %d", rc);
    _exit(rc);
}
static void env_file(const char *path) {   // KEY=VALUE per line (# comments): switches without rebuilding, as the iOS app's Documents/env.txt
    FILE *f = fopen(path, "r"); char line[1024];
    if (!f) return;
    while (fgets(line, sizeof line, f)) {
        char *e = line + strlen(line); while (e > line && (e[-1] == '\n' || e[-1] == '\r' || e[-1] == ' ')) *--e = 0;
        if (!line[0] || line[0] == '#') continue;
        char *eq = strchr(line, '='); if (!eq) continue; *eq = 0; setenv(line, eq + 1, 1);
    }
    fclose(f);
}
static int g_host_up;   // the host is running (the setup is over)
static void host_start_common(JNIEnv *E, JavaVM *vm, jobject actctx, const char *internal) {
    if (g_host_up) return;
    crash_install();   // the signal handlers: the Android host has no main.c to install them, so a crash here used to kill the game silently
    char p[700], q[720];
    snprintf(p, sizeof p, "%s/log.txt", g_root); char prev[720]; snprintf(prev, sizeof prev, "%s/log.prev.txt", g_root); rename(p, prev);
    if (!freopen(p, "w", stderr)) __android_log_print(ANDROID_LOG_ERROR, "dmhost", "cannot write %s", p);
    setvbuf(stderr, NULL, _IOLBF, 0);
    setenv("DM_ROOT", g_root, 1);
    snprintf(p, sizeof p, "%s/RomFS", g_root); setenv("DM_ROMFS", p, 0);
    // the player's games share the app's one internal folder: the title id (dm_java_start's DM_GAME_ID) keeps the saves and the temp apart
    const char *gid = getenv("DM_GAME_ID"); char sub[80] = "";
    if (gid && gid[0] && strlen(gid) < sizeof sub - 2) snprintf(sub, sizeof sub, "/%s", gid);
    snprintf(p, sizeof p, "%s/save", internal); mkdir(p, 0700); snprintf(q, sizeof q, "%s%s", p, sub); mkdir(q, 0700); setenv("DM_SAVE", q, 0);
    snprintf(p, sizeof p, "%s/temp", internal); mkdir(p, 0700); snprintf(q, sizeof q, "%s%s", p, sub); mkdir(q, 0700); setenv("DM_TEMP", q, 0);
    snprintf(p, sizeof p, "%s/shaders.dmsv", g_root); setenv("DM_VK_SHADERS", p, 0);
    snprintf(p, sizeof p, "%s/env.txt", g_root); env_file(p);
    // the save manager's requests (saves_android.c), before the host mounts the save: an import the player asked for in the drawer, an export porter asks for
    if (dm_saves_start(g_root)) { LOG("[saves] the request asked the app to end: the game is not started"); return; }
    settings_load(); dm_haptics_android_start(E, vm, actctx, atomic_load(&g_set_rumble));
    LOG("NX2A host on Android: files %s, saves %s", g_root, internal);
    pthread_t t; pthread_create(&t, NULL, input_thread, NULL); pthread_detach(t);
    // The game's main thread: a 16 MB stack mapped here and handed over whole (pthread_attr_setstack), so the range pthread_getattr_np reports, which
    // svcQueryMemory gives the game as its main thread stack (hle_svc.c), is exactly mapped memory: IL2CPP's garbage collector scans that range and
    // with bionic's own stack the bottom of the reported stack was not readable (SIGSEGV at main+0x2a59b4). A guard page sits below it.
    size_t ssz = 16 << 20, guard = 64 << 10;
    uint8_t *stk = mmap(NULL, ssz + guard, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    pthread_attr_t at; pthread_attr_init(&at);
    if (stk != MAP_FAILED) { mprotect(stk, guard, PROT_NONE); pthread_attr_setstack(&at, stk + guard, ssz); } else pthread_attr_setstacksize(&at, ssz);
    if (pthread_create(&t, &at, host_thread, NULL)) LOG("[RED] cannot start the host thread"); else pthread_detach(t);
    pthread_attr_destroy(&at);
    g_host_up = 1;
}

// ---- the host's notices to the app (the player): the menu button was tapped (the app opens its drawer) ----
// The notice goes on a thread of its own: it is a JNI call into the Activity, and the caller's thread must never wait for the app (the game's thread
// blocked there once, and the game froze with no keyboard).
static jmethodID g_app_notice;
#define NOTICE_Q 8
static pthread_mutex_t g_notice_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_notice_cv = PTHREAD_COND_INITIALIZER;
static int g_notice_q[NOTICE_Q], g_notice_n;
static void *notice_thread(void *a) {
    (void)a; crash_thread_init();
    JNIEnv *E = NULL;
    if ((*g_app_vm)->AttachCurrentThread(g_app_vm, &E, NULL) != JNI_OK) { LOG("[RED] [app] cannot attach to the Java VM: the notices are lost"); return NULL; }
    for (;;) {
        pthread_mutex_lock(&g_notice_mu);
        while (!g_notice_n) pthread_cond_wait(&g_notice_cv, &g_notice_mu);
        int what = g_notice_q[0];
        for (int i = 1; i < g_notice_n; i++) g_notice_q[i - 1] = g_notice_q[i];
        g_notice_n--;
        pthread_mutex_unlock(&g_notice_mu);
        (*E)->CallVoidMethod(E, g_app_act, g_app_notice, (jint)what);
        if ((*E)->ExceptionCheck(E)) { (*E)->ExceptionClear(E); LOG("[RED] [app] the notice (%d) failed", what); }
    }
    return NULL;
}
void dm_app_notice(int what) {
    if (!g_app_vm || !g_app_act || !g_app_notice) return;
    pthread_mutex_lock(&g_notice_mu);
    if (g_notice_n == NOTICE_Q) { memmove(g_notice_q, g_notice_q + 1, (NOTICE_Q - 1) * sizeof(int)); g_notice_n--; LOG("[RED] [app] the notice queue is full: the oldest notice is dropped"); }
    g_notice_q[g_notice_n++] = what;
    pthread_cond_signal(&g_notice_cv);
    pthread_mutex_unlock(&g_notice_mu);
}
static void app_notice_keep(JNIEnv *E, JavaVM *vm, jobject act) {   // the start call runs on Java's thread: the Activity and its method are looked up here
    g_app_vm = vm;
    g_app_act = (*E)->NewGlobalRef(E, act);
    jclass c = (*E)->GetObjectClass(E, act);
    g_app_notice = c ? (*E)->GetMethodID(E, c, "onHostNotice", "(I)V") : NULL;
    if ((*E)->ExceptionCheck(E)) { (*E)->ExceptionClear(E); g_app_notice = NULL; }   // an older app has no such method
    if (!g_app_act || !g_app_notice) {
        LOG("[RED] [app] cannot reach the Activity: the host's notices are lost");
        if (g_app_act) { (*E)->DeleteGlobalRef(E, g_app_act); g_app_act = NULL; }
        g_app_notice = NULL;
    }
    static int started;
    if (!started && g_app_act && g_app_notice) {   // the notices go on a thread of their own (dm_app_notice)
        started = 1; pthread_t t; if (!pthread_create(&t, NULL, notice_thread, NULL)) pthread_detach(t);
    }
}

// ---- the player's start (jni_android.h): the pack, the app's own folder, the title id, the display's density ----
int dm_java_start(const char *pack, const char *internal, const char *title_id, int dpi, JNIEnv *E, jobject ctx) {
    if (g_host_up) return 1;
    JavaVM *vm = NULL; if (E) (*E)->GetJavaVM(E, &vm);
    g_dpi = dpi;
    if (title_id && title_id[0]) setenv("DM_GAME_ID", title_id, 1);
    snprintf(g_root, sizeof g_root, "%s", pack);
    // the player's control file lives in the app's private folder: only the app can write it, so the drawer's
    // live switches (DM_LSFG* and friends) work in release builds too, with no adb key presses reachable
    { char ctl[700]; snprintf(ctl, sizeof ctl, "%s/ctl-%s.txt", internal, title_id && title_id[0] ? title_id : "game"); setenv("DM_CTL", ctl, 1); }
    if (E && vm && ctx) app_notice_keep(E, vm, ctx);   // the host's notices to the app (the Activity keeps the drawer's state)
    host_start_common(E, vm, ctx, internal);
    return g_host_up ? 1 : 0;
}
