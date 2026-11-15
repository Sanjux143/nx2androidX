// The physical controller's keys: the player's own bindings, the default dictionary and the per-device calibration.
//
// Pure: no Android, no host state. The phone's front end (front_player.c) and the tests (tests/test_pad_map.py) use it.
//
// The player's bindings come from the app (the gamepad center): one line per binding, keyed by the device's
// Android id, so two controllers of the same model keep their own tables. The text is line-based, not JSON (the
// host has no JSON parser):
//
//   v1
//   dev 3 labels=1 dz=0.15 tr=0.55 player=1
//   bind scan:305 a
//   unbind scan:314
//   dev 7 labels=0 dz=0.12 tr=0.5
//
// A source is "scan:<n>" (the physical scan code, the same button on every phone), "key:<n>" (Android's key code)
// or "axis:<id>:<sign>" (half an analog axis: the trigger or the hat). A target is one of the game's buttons
// ("a" ... "right"), or the disable marker.
//
// The "player" of a device is which player it plays: 0 is player 1 (the default, so a table without it behaves
// as it always did), 1..7 are players 2..8, and -1 means the player set that controller aside (it does nothing).
//
// The default dictionary is what the game sees when the player changed nothing: the Switch's layout by position
// (X on top, Y on the left, A on the right, B at the bottom). The position comes from the scan code, which is the
// same on every controller; Android's key codes only say what the system calls a button, and that is not the same
// everywhere (each vendor has its own key layout), so they are only the fallback for the buttons that carry no
// scan code (the d-pad). With "labels" the face buttons go by their print instead (an Xbox controller's A is the
// game's A): the print belongs to the controller, not to the game.
#ifndef DM_PAD_MAP_H
#define DM_PAD_MAP_H

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dm_input.h"   // the game's button bits (DM_BTN_*)

// Android's key codes (KeyEvent's): the same values as the AKEYCODE_* the front end uses.
enum {
    PAD_KEY_DPAD_UP = 19, PAD_KEY_DPAD_DOWN = 20, PAD_KEY_DPAD_LEFT = 21, PAD_KEY_DPAD_RIGHT = 22,
    PAD_KEY_BUTTON_A = 96, PAD_KEY_BUTTON_B = 97, PAD_KEY_BUTTON_X = 99, PAD_KEY_BUTTON_Y = 100,
    PAD_KEY_BUTTON_L1 = 102, PAD_KEY_BUTTON_R1 = 103, PAD_KEY_BUTTON_L2 = 104, PAD_KEY_BUTTON_R2 = 105,
    PAD_KEY_BUTTON_THUMBL = 106, PAD_KEY_BUTTON_THUMBR = 107, PAD_KEY_BUTTON_START = 108, PAD_KEY_BUTTON_SELECT = 109,
};

// The Linux scan codes: they say where the button IS (the bottom one is 304 on every controller).
// Android's key codes only say what the system CALLS the button, and that is not the same on every
// controller (each vendor has its own key layout), so the dictionary follows the scan code.
enum {
    PAD_SCAN_SOUTH = 304, PAD_SCAN_EAST = 305, PAD_SCAN_NORTH = 307, PAD_SCAN_WEST = 308,
    PAD_SCAN_L1 = 310, PAD_SCAN_R1 = 311, PAD_SCAN_L2 = 312, PAD_SCAN_R2 = 313,
    PAD_SCAN_SELECT = 314, PAD_SCAN_START = 315, PAD_SCAN_L3 = 317, PAD_SCAN_R3 = 318,
};

// The game's buttons, in the order the center lists them. The bits are the host's own (dm_input.h): the name is
// what the app pushes, the bit is what the game reads.
static const struct { const char *name; uint64_t bit; } pad_buttons[] = {
    {"a", DM_BTN_A}, {"b", DM_BTN_B}, {"x", DM_BTN_X}, {"y", DM_BTN_Y},
    {"l", DM_BTN_L}, {"r", DM_BTN_R}, {"zl", DM_BTN_ZL}, {"zr", DM_BTN_ZR},
    {"ls", DM_BTN_LS}, {"rs", DM_BTN_RS}, {"plus", DM_BTN_PLUS}, {"minus", DM_BTN_MINUS},
    {"up", DM_BTN_UP}, {"down", DM_BTN_DOWN}, {"left", DM_BTN_LEFT}, {"right", DM_BTN_RIGHT},
};
enum { PAD_N = sizeof pad_buttons / sizeof pad_buttons[0] };

// The disable marker: the source is there, but it does nothing.
#define PAD_DISABLED "__disabled__"

// The defaults: the stick's dead zone and the trigger's threshold (what the host uses today).
#define PAD_DEFAULT_DEADZONE 0.12f
#define PAD_DEFAULT_TRIGGER 0.5f

// The Android axis ids the event carries (MotionEvent's): the event's axis array is X Y Z RZ LTRIGGER RTRIGGER HAT_X HAT_Y BRAKE GAS.
enum {
    PAD_AXIS_LTRIGGER = 17, PAD_AXIS_RTRIGGER = 18, PAD_AXIS_HAT_X = 15, PAD_AXIS_HAT_Y = 16,
    PAD_AXIS_GAS = 22, PAD_AXIS_BRAKE = 23,
};

#define PAD_MAP_MAX_DEVICES 8
#define PAD_MAP_MAX_BINDINGS 64
#define PAD_MAP_SRC_MAX 24

// One binding: the source (a "scan:305" or "key:96" string) and the target (a PAD_* index, or -1 for disabled).
typedef struct { char source[PAD_MAP_SRC_MAX]; int target; } pad_map_binding;

// One device's table: the bindings, the face buttons' flip, the calibration and the player it plays.
typedef struct {
    int device;          // the Android input device id
    int labels;          // 1: the face buttons by their label, 0: by their position
    int player;          // 0: player 1, 1..7: players 2..8, -1: set aside (the controller does nothing)
    float deadzone;      // < 0: the default
    float trigger;       // < 0: the default
    int nbindings;
    pad_map_binding bindings[PAD_MAP_MAX_BINDINGS];
} pad_map_device;

typedef struct { int n; pad_map_device devices[PAD_MAP_MAX_DEVICES]; } pad_map_t;

// ---- the button table ----
static inline int pad_button_index(const char *name) {
    if (!name) return -1;
    for (int i = 0; i < PAD_N; i++) if (!strcmp(name, pad_buttons[i].name)) return i;
    return -1;
}
static inline uint64_t pad_button_bit(int index) { return index < 0 || index >= PAD_N ? 0 : pad_buttons[index].bit; }

// The name of the game's button a bit stands for ("a", "b", ...; "(none)" when the key does nothing).
static inline const char *pad_button_name(uint64_t bit) {
    if (!bit) return "(none)";
    for (int i = 0; i < PAD_N; i++) if (pad_buttons[i].bit == bit) return pad_buttons[i].name;
    return "?";
}

// The keys' debug log (DM_PAD_LOG=1 in the pack's env.txt): what each key event became for the game.
static inline int pad_log_keys(void) {
    static int on = -1;
    if (on < 0) { const char *e = getenv("DM_PAD_LOG"); on = e && atoi(e) ? 1 : 0; }
    return on;
}

// The default dictionary: the Switch's layout by position (X on top, Y on the left, A on the right, B at the bottom).
// The game speaks Switch positions; what the controller prints on its buttons is only a sticker.
static inline uint64_t pad_default_bit(int scan, int key, int labels) {
    if (scan > 0) {   // by position: the scan code says where the button IS (the same on every controller)
        switch (scan) {
        case PAD_SCAN_SOUTH: return labels ? DM_BTN_A : DM_BTN_B;
        case PAD_SCAN_EAST: return labels ? DM_BTN_B : DM_BTN_A;
        case PAD_SCAN_NORTH: return labels ? DM_BTN_Y : DM_BTN_X;
        case PAD_SCAN_WEST: return labels ? DM_BTN_X : DM_BTN_Y;
        case PAD_SCAN_L1: return DM_BTN_L; case PAD_SCAN_R1: return DM_BTN_R;
        case PAD_SCAN_L2: return DM_BTN_ZL; case PAD_SCAN_R2: return DM_BTN_ZR;
        case PAD_SCAN_L3: return DM_BTN_LS; case PAD_SCAN_R3: return DM_BTN_RS;
        case PAD_SCAN_SELECT: return DM_BTN_MINUS; case PAD_SCAN_START: return DM_BTN_PLUS;
        default: break;
        }
    }
    // No scan code (the d-pad is the one that has none): the key code's name it is.
    switch (key) {
    case PAD_KEY_BUTTON_A: return labels ? DM_BTN_A : DM_BTN_B;
    case PAD_KEY_BUTTON_B: return labels ? DM_BTN_B : DM_BTN_A;
    case PAD_KEY_BUTTON_X: return labels ? DM_BTN_Y : DM_BTN_X;
    case PAD_KEY_BUTTON_Y: return labels ? DM_BTN_X : DM_BTN_Y;
    case PAD_KEY_BUTTON_L1: return DM_BTN_L; case PAD_KEY_BUTTON_R1: return DM_BTN_R;
    case PAD_KEY_BUTTON_L2: return DM_BTN_ZL; case PAD_KEY_BUTTON_R2: return DM_BTN_ZR;
    case PAD_KEY_BUTTON_THUMBL: return DM_BTN_LS; case PAD_KEY_BUTTON_THUMBR: return DM_BTN_RS;
    case PAD_KEY_BUTTON_START: return DM_BTN_PLUS; case PAD_KEY_BUTTON_SELECT: return DM_BTN_MINUS;
    case PAD_KEY_DPAD_UP: return DM_BTN_UP; case PAD_KEY_DPAD_DOWN: return DM_BTN_DOWN;
    case PAD_KEY_DPAD_LEFT: return DM_BTN_LEFT; case PAD_KEY_DPAD_RIGHT: return DM_BTN_RIGHT;
    default: return 0;
    }
}

// The face buttons' flip for a device without a table: the environment decides (DM_PAD_LABEL, or its first name
// DM_PAD_XBOX), the default being the position.
static inline int pad_env_labels(void) {
    const char *e = getenv("DM_PAD_LABEL"); if (!e) e = getenv("DM_PAD_XBOX");
    return e && atoi(e) ? 1 : 0;
}
// The same, read once (the environment does not change while the game runs).
static inline int pad_env_labels_cached(void) { static int on = -1; if (on < 0) on = pad_env_labels(); return on; }

// ---- the table ----
static inline void pad_map_clear(pad_map_t *m) { memset(m, 0, sizeof *m); }

static inline pad_map_device *pad_map_device_mut(pad_map_t *m, int device) {
    for (int i = 0; i < m->n; i++) if (m->devices[i].device == device) return &m->devices[i];
    if (m->n >= PAD_MAP_MAX_DEVICES) return NULL;
    pad_map_device *d = &m->devices[m->n++];
    memset(d, 0, sizeof *d);
    d->device = device; d->labels = -1; d->player = 0; d->deadzone = -1; d->trigger = -1;   // -1: the environment decides
    return d;
}

static inline const pad_map_device *pad_map_device_of(const pad_map_t *m, int device) {
    for (int i = 0; i < m->n; i++) if (m->devices[i].device == device) return &m->devices[i];
    return NULL;
}

// The value of a "key=value" pair in a "dev" line; -1 when it is not there.
static inline int pad_map_value(const char *line, const char *key, int fallback) {
    size_t n = strlen(key);
    for (const char *p = line; (p = strstr(p, key)); p += n) {
        if (p != line && p[-1] != ' ') continue;   // the key's own word
        if (p[n] != '=') continue;
        return atoi(p + n + 1);
    }
    return fallback;
}

static inline float pad_map_float(const char *line, const char *key, float fallback) {
    size_t n = strlen(key);
    for (const char *p = line; (p = strstr(p, key)); p += n) {
        if (p != line && p[-1] != ' ') continue;
        if (p[n] != '=') continue;
        return (float)atof(p + n + 1);
    }
    return fallback;
}

// The table from the app's text. A line that is not understood is skipped: a broken table is a table with fewer
// entries, never a crash.
static inline int pad_map_parse(pad_map_t *m, const char *spec) {
    pad_map_clear(m);
    if (!spec) return 0;
    char buf[4096];
    size_t len = strlen(spec);
    if (len >= sizeof buf) len = sizeof buf - 1;
    memcpy(buf, spec, len); buf[len] = 0;
    pad_map_device *cur = NULL;
    for (char *line = strtok(buf, "\r\n"); line; line = strtok(NULL, "\r\n")) {
        while (*line == ' ') line++;
        if (!strncmp(line, "dev ", 4)) {
            int device = atoi(line + 4);
            cur = pad_map_device_mut(m, device);
            if (!cur) continue;
            cur->labels = pad_map_value(line, "labels", -1);
            cur->player = pad_map_value(line, "player", 0);   // 0: player 1 (the default), -1: set aside
            cur->deadzone = pad_map_float(line, "dz", -1);
            cur->trigger = pad_map_float(line, "tr", -1);
        } else if (!strncmp(line, "bind ", 5) && cur) {
            char source[PAD_MAP_SRC_MAX] = {0}, target[PAD_MAP_SRC_MAX] = {0};
            if (sscanf(line + 5, "%23s %23s", source, target) != 2) continue;
            int t = pad_button_index(target);
            if (t < 0) continue;
            if (cur->nbindings >= PAD_MAP_MAX_BINDINGS) continue;
            snprintf(cur->bindings[cur->nbindings].source, PAD_MAP_SRC_MAX, "%s", source);
            cur->bindings[cur->nbindings].target = t;
            cur->nbindings++;
        } else if (!strncmp(line, "unbind ", 7) && cur) {
            char source[PAD_MAP_SRC_MAX] = {0};
            if (sscanf(line + 7, "%23s", source) != 1) continue;
            if (cur->nbindings >= PAD_MAP_MAX_BINDINGS) continue;
            snprintf(cur->bindings[cur->nbindings].source, PAD_MAP_SRC_MAX, "%s", source);
            cur->bindings[cur->nbindings].target = -1;   // disabled
            cur->nbindings++;
        }
    }
    return m->n;
}

// The binding for a source ("scan:305" or "key:96"); NULL when the player changed nothing.
static inline const pad_map_binding *pad_map_find(const pad_map_device *d, const char *kind, int value) {
    if (!d) return NULL;
    char source[PAD_MAP_SRC_MAX];
    snprintf(source, sizeof source, "%s:%d", kind, value);
    for (int i = 0; i < d->nbindings; i++) if (!strcmp(d->bindings[i].source, source)) return &d->bindings[i];
    return NULL;
}

// The binding for an axis half ("axis:17:+"); NULL when there is none.
static inline const pad_map_binding *pad_map_find_axis(const pad_map_device *d, int axis, int sign) {
    if (!d) return NULL;
    char source[PAD_MAP_SRC_MAX];
    snprintf(source, sizeof source, "axis:%d:%c", axis, sign < 0 ? '-' : '+');
    for (int i = 0; i < d->nbindings; i++) if (!strcmp(d->bindings[i].source, source)) return &d->bindings[i];
    return NULL;
}

// The bit a key event carries: the player's own binding first, the default dictionary otherwise.
static inline uint64_t pad_map_key_bit(const pad_map_t *m, int device, int scan, int key) {
    const pad_map_device *d = pad_map_device_of(m, device);
    if (d) {
        if (scan > 0) {
            const pad_map_binding *b = pad_map_find(d, "scan", scan);
            if (b) return b->target < 0 ? 0 : pad_button_bit(b->target);
        }
        const pad_map_binding *b = pad_map_find(d, "key", key);
        if (b) return b->target < 0 ? 0 : pad_button_bit(b->target);
    }
    int labels = d ? (d->labels >= 0 ? d->labels : pad_env_labels_cached()) : pad_env_labels_cached();
    return pad_default_bit(scan, key, labels);
}

// The face buttons' flip for a device: the table's, else the environment's.
static inline int pad_map_labels(const pad_map_t *m, int device) {
    const pad_map_device *d = pad_map_device_of(m, device);
    if (!d || d->labels < 0) return pad_env_labels_cached();
    return d->labels;
}

// The player a device plays: 0 is player 1 (the default: a controller the table does not know plays player 1),
// 1..7 are players 2..8, -1 means the player set that controller aside (it does nothing).
static inline int pad_map_player(const pad_map_t *m, int device) {
    const pad_map_device *d = pad_map_device_of(m, device);
    return d ? d->player : 0;
}

// The stick's dead zone and the trigger's threshold for a device (the defaults when it has none).
static inline float pad_map_deadzone(const pad_map_t *m, int device) {
    const pad_map_device *d = pad_map_device_of(m, device);
    return d && d->deadzone >= 0 ? d->deadzone : PAD_DEFAULT_DEADZONE;
}
static inline float pad_map_trigger(const pad_map_t *m, int device) {
    const pad_map_device *d = pad_map_device_of(m, device);
    return d && d->trigger >= 0 ? d->trigger : PAD_DEFAULT_TRIGGER;
}

// The bit an analog axis carries: the player's own binding for that half of the axis, else nothing (the triggers
// and the hat are the front end's own business).
static inline uint64_t pad_map_axis_bit(const pad_map_t *m, int device, int axis, float value) {
    const pad_map_device *d = pad_map_device_of(m, device);
    if (!d) return 0;
    const pad_map_binding *b = pad_map_find_axis(d, axis, value < 0 ? -1 : 1);
    if (!b) return 0;
    return b->target < 0 ? 0 : pad_button_bit(b->target);
}

// True when the player bound that axis themselves: the front end's own trigger then does not fire for it.
static inline int pad_map_has_axis(const pad_map_t *m, int device, int axis) {
    const pad_map_device *d = pad_map_device_of(m, device);
    if (!d) return 0;
    return pad_map_find_axis(d, axis, -1) || pad_map_find_axis(d, axis, 1);
}

#endif // DM_PAD_MAP_H
