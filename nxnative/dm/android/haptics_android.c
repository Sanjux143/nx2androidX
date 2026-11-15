// Android port: vibration. The game's rumble (hle_haptics.c's state, one per player) and the touch gamepad's taps go to android.os.Vibrator through JNI, on a
// thread of its own: the haptics sink runs on the game's thread under hle_haptics.c's lock, and every vibrate call is a binder transaction. Each player's rumble goes to the
// controller that plays that player (the front end says which device that is), and a player without a controller falls back to the device's own body (the players without one share
// it: the strongest of them wins). The touch gamepad's taps always go to the device. The player's Rumble setting turns both off.
// Android has no continuous rumble with a changing strength, so a strength is a one-shot of RUMBLE_MS renewed while the game keeps the motor running.
// The strength follows an envelope (BananaNative's Rumble.java): every TICK_NS the strongest level the game set since the last tick, not the latest one, and once the
// game goes quiet a fall of FALL a tick (about 0.2 s from full to off). Switch 2 games stream HD rumble (Donkey Kong Bananza: 400 values a second, short peaks with
// near-silence between); taking the latest value mostly lands on the silence or cuts each buzz short, so the motor never spins up.
#include "dm.h"
#include "hle_haptics.h"
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define RUMBLE_MS 600
#define RENEW_NS 400000000ll
#define TICK_NS 40000000ll   // the envelope's step
#define FALL 0.6f            // the envelope's fall per tick once the game is quiet
#define MOVE 6               // a new vibration only when the strength moved this much (of 255)
#define GAP_NS 25000000ll    // a running rumble waits this long after a tap, so it does not replace the tap
static JavaVM *g_vm; static jobject g_actobj;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER; static pthread_cond_t g_cv = PTHREAD_COND_INITIALIZER;
static dm_haptic_state g_state[DM_PLAYERS];   // the game's last rumble, per player
static float g_peak[DM_PLAYERS];              // the strongest level since the last tick, per player
static float g_env[DM_PLAYERS];               // the envelope, per player (the thread's own)
static int g_taps, g_clear;                   // g_clear: a bit per player (a new epoch: the older level is dropped)
static int g_dev[DM_PLAYERS];                 // the controller that plays each player (-1: the device's own body)
static int g_dev_new;                         // a device changed: the vibrators are taken again
static _Atomic int g_on = 1;
static int64_t mono_now(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec; }
static void sink(dm_haptic_state s) {   // hle_haptics.c, under its lock: only hand the state over
    int p = s.player;
    if (p < 0 || p >= DM_PLAYERS) return;
    float lv = dm_haptics_level(s.motor, 2, 1.0f).intensity;
    pthread_mutex_lock(&g_mu);
    if (s.epoch != g_state[p].epoch) { g_peak[p] = 0; g_clear |= 1 << p; pthread_cond_signal(&g_cv); }   // a new epoch drops everything older: stop at once, no fall
    g_state[p] = s; if (lv > g_peak[p]) { if (g_peak[p] <= 0) pthread_cond_signal(&g_cv); g_peak[p] = lv; }   // a wake only to start ticking
    pthread_mutex_unlock(&g_mu);
}
void dm_haptics_android_tap(void) { if (!atomic_load(&g_on) || !g_vm) return; pthread_mutex_lock(&g_mu); g_taps++; pthread_cond_signal(&g_cv); pthread_mutex_unlock(&g_mu); }
void dm_haptics_android_device_player(int player, int id) {   // the controller that plays this player (-1: the device's own body)
    if (player < 0 || player >= DM_PLAYERS) return;
    pthread_mutex_lock(&g_mu); if (id != g_dev[player]) { g_dev[player] = id; g_dev_new = 1; pthread_cond_signal(&g_cv); } pthread_mutex_unlock(&g_mu);
}
void dm_haptics_android_set(int on) { atomic_store(&g_on, on != 0); pthread_mutex_lock(&g_mu); pthread_cond_signal(&g_cv); pthread_mutex_unlock(&g_mu); }

static JNIEnv *E;
static jclass cVE, cID; static jmethodID mOneShot, mPredef, mVibrate, mCancel, mHas, mAmp, mGetDevice, mGetVib, mGetVibMgr, mGetDefault;
typedef struct { jobject v; int amp; } vib_t;   // a Vibrator (global reference) and whether it controls amplitude
typedef struct { vib_t *v; int amp; int64_t last; } chan_t;   // what is playing now: the target, the strength, when it started
static int jok(const char *what) {
    if (!(*E)->ExceptionCheck(E)) return 1;
    (*E)->ExceptionClear(E);
    // the same failure repeats (the device's vibrator refuses every call): it is logged once per kind, or the log drowns in it
    static const char *seen[8]; static int nseen;
    for (int i = 0; i < nseen; i++) if (!strcmp(seen[i], what)) return 0;
    if (nseen < 8) seen[nseen++] = what;
    LOG("[RED] [haptics] Java exception in %s", what); return 0;
}
static int vib_take(vib_t *v, jobject o) {   // o: a local reference, released here
    v->v = NULL; v->amp = 0;
    if (!o) return 0;
    jboolean has = (*E)->CallBooleanMethod(E, o, mHas); if (!jok("hasVibrator")) has = 0;
    if (has) { v->v = (*E)->NewGlobalRef(E, o); v->amp = (*E)->CallBooleanMethod(E, o, mAmp); if (!jok("hasAmplitudeControl")) v->amp = 0; }
    (*E)->DeleteLocalRef(E, o);
    return v->v != NULL;
}
static void vib_drop(vib_t *v) { if (v->v) (*E)->DeleteGlobalRef(E, v->v); v->v = NULL; }
static void vib_effect(vib_t *v, jobject e) { if (!e) return; (*E)->CallVoidMethod(E, v->v, mVibrate, e); jok("vibrate"); (*E)->DeleteLocalRef(E, e); }
static void vib_play(vib_t *v, int ms, int amp) { jobject e = (*E)->CallStaticObjectMethod(E, cVE, mOneShot, (jlong)ms, (jint)(v->amp ? amp : -1)); if (jok("createOneShot")) vib_effect(v, e); }
static void vib_tap(vib_t *v) {   // a short, light tick; without amplitude control the system's click
    if (v->amp) { vib_play(v, 14, 140); return; }
    jobject e = (*E)->CallStaticObjectMethod(E, cVE, mPredef, (jint)0 /* EFFECT_CLICK */); if (jok("createPredefined")) vib_effect(v, e);
}
static void vib_stop(vib_t *v) { (*E)->CallVoidMethod(E, v->v, mCancel); jok("cancel"); }

static void *haptics_thread(void *a) {
    (void)a; pthread_setname_np(pthread_self(), "haptics"); crash_thread_init();
    if ((*g_vm)->AttachCurrentThread(g_vm, &E, NULL) != JNI_OK) { LOG("[RED] [haptics] cannot attach to the Java VM"); return NULL; }
    jclass cCtx = (*E)->FindClass(E, "android/content/Context"), cVib = (*E)->FindClass(E, "android/os/Vibrator");
    cVE = (*E)->NewGlobalRef(E, (*E)->FindClass(E, "android/os/VibrationEffect")); cID = (*E)->NewGlobalRef(E, (*E)->FindClass(E, "android/view/InputDevice"));
    if (!jok("FindClass") || !cCtx || !cVib || !cVE || !cID) { LOG("[RED] [haptics] Vibrator classes missing"); return NULL; }
    jmethodID mSvc = (*E)->GetMethodID(E, cCtx, "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;");
    mOneShot = (*E)->GetStaticMethodID(E, cVE, "createOneShot", "(JI)Landroid/os/VibrationEffect;");
    mPredef = (*E)->GetStaticMethodID(E, cVE, "createPredefined", "(I)Landroid/os/VibrationEffect;");
    mVibrate = (*E)->GetMethodID(E, cVib, "vibrate", "(Landroid/os/VibrationEffect;)V"); mCancel = (*E)->GetMethodID(E, cVib, "cancel", "()V");
    mHas = (*E)->GetMethodID(E, cVib, "hasVibrator", "()Z"); mAmp = (*E)->GetMethodID(E, cVib, "hasAmplitudeControl", "()Z");
    mGetDevice = (*E)->GetStaticMethodID(E, cID, "getDevice", "(I)Landroid/view/InputDevice;"); mGetVib = (*E)->GetMethodID(E, cID, "getVibrator", "()Landroid/os/Vibrator;");
    if (!jok("GetMethodID")) { LOG("[RED] [haptics] Vibrator methods missing"); return NULL; }
    int sdk = 0;   // Android 11 and newer: the device's motor is behind the vibrator manager, not the device itself
    jclass cBuild = (*E)->FindClass(E, "android/os/Build$VERSION");
    if (cBuild) { jfieldID f = (*E)->GetStaticFieldID(E, cBuild, "SDK_INT", "I"); if (f && jok("GetStaticFieldID")) sdk = (*E)->GetStaticIntField(E, cBuild, f); }
    if (sdk >= 31) {
        mGetVibMgr = (*E)->GetMethodID(E, cID, "getVibratorManager", "()Landroid/os/VibratorManager;");
        jclass cVM = (*E)->FindClass(E, "android/os/VibratorManager");
        if (cVM) mGetDefault = (*E)->GetMethodID(E, cVM, "getDefaultVibrator", "()Landroid/os/Vibrator;");
        if (!mGetVibMgr || !mGetDefault) { mGetVibMgr = NULL; mGetDefault = NULL; }
    }
    LOG("[haptics] the Android API level %d%s", sdk, mGetVibMgr ? " (the vibrator manager)" : " (no vibrator manager)");
    jstring name = (*E)->NewStringUTF(E, "vibrator");
    vib_t dev; vib_take(&dev, (*E)->CallObjectMethod(E, g_actobj, mSvc, name)); jok("getSystemService"); (*E)->DeleteLocalRef(E, name);
    LOG("[haptics] device vibrator: %s%s", dev.v ? "yes" : "none", dev.v ? dev.amp ? ", with amplitude control" : ", on/off only" : "");
    dm_haptics_route(0, 1);   // rumble has somewhere to go from now on (a controller's motor, or the device's)
    vib_t pad[DM_PLAYERS]; int pad_id[DM_PLAYERS]; chan_t ch[DM_PLAYERS]; chan_t phone;
    for (int p = 0; p < DM_PLAYERS; p++) { memset(&pad[p], 0, sizeof pad[p]); pad_id[p] = -2; memset(&ch[p], 0, sizeof ch[p]); }
    memset(&phone, 0, sizeof phone);
    int was_on = 1; int64_t last = 0, next = 0, tap_end = 0;
    for (;;) {
        pthread_mutex_lock(&g_mu);
        for (;;) {   // asleep until a tap, a device change, the setting, or rumble to play; while the envelope runs, a tick every TICK_NS
            int64_t t = mono_now(); int on = atomic_load(&g_on);
            if (g_taps || g_dev_new || g_clear) break;
            if (on != was_on) { next = t; break; }   // the setting changed: a tick now, so a held rumble comes back or stops
            int busy = phone.amp != 0;
            for (int p = 0; p < DM_PLAYERS; p++) if (g_peak[p] > 0 || g_env[p] > 0 || ch[p].amp) busy = 1;
            if (!busy) { pthread_cond_wait(&g_cv, &g_mu); continue; }   // idle: sleep until a level arrives
            if (t >= next) break;
            struct timespec dl; clock_gettime(CLOCK_REALTIME, &dl); int64_t w = next - t; dl.tv_sec += w / 1000000000; dl.tv_nsec += w % 1000000000;
            if (dl.tv_nsec >= 1000000000) { dl.tv_sec++; dl.tv_nsec -= 1000000000; }
            pthread_cond_timedwait(&g_cv, &g_mu, &dl);
        }
        int taps = g_taps, dn = g_dev_new, clear = g_clear;
        dm_haptic_state st[DM_PLAYERS]; float peak[DM_PLAYERS]; int ids[DM_PLAYERS];
        memcpy(st, g_state, sizeof st); memcpy(peak, g_peak, sizeof peak); memcpy(ids, g_dev, sizeof ids);
        g_taps = 0; g_dev_new = 0; g_clear = 0;
        int64_t t = mono_now(), tick = t >= next;
        if (tick) for (int p = 0; p < DM_PLAYERS; p++) g_peak[p] = 0;
        pthread_mutex_unlock(&g_mu);
        was_on = atomic_load(&g_on);
        if (dn) {   // a controller came, went, or changed player: its motor is taken again
            for (int p = 0; p < DM_PLAYERS; p++) if (pad_id[p] != ids[p]) {
                if (ch[p].v == &pad[p]) { vib_stop(&pad[p]); ch[p].v = NULL; ch[p].amp = 0; }
                vib_drop(&pad[p]);
                if (ids[p] >= 0) {
                    jobject d = (*E)->CallStaticObjectMethod(E, cID, mGetDevice, (jint)ids[p]);
                    if (!jok("getDevice") || !d) LOG("[RED] [haptics] the input device %d is not there", ids[p]);
                    else {
                        jobject v = (*E)->CallObjectMethod(E, d, mGetVib);
                        if (jok("getVibrator") && v) vib_take(&pad[p], v);
                        if (!pad[p].v && mGetVibMgr) {   // Android 11 and newer: the device's motor is behind the vibrator manager
                            jobject mgr = (*E)->CallObjectMethod(E, d, mGetVibMgr);
                            if (jok("getVibratorManager") && mgr) {
                                jobject dv = (*E)->CallObjectMethod(E, mgr, mGetDefault);
                                if (jok("getDefaultVibrator") && dv) vib_take(&pad[p], dv);
                                (*E)->DeleteLocalRef(E, mgr);
                            }
                        }
                        (*E)->DeleteLocalRef(E, d);
                    }
                }
                pad_id[p] = ids[p];
                if (ids[p] >= 0) LOG("[haptics] player %d: the input device %d's rumble on %s", p + 1, ids[p], pad[p].v ? pad[p].amp ? "the controller (amplitude control)" : "the controller (on/off)" : "the device (the controller has no motor)");
            }
        }
        if (clear) for (int p = 0; p < DM_PLAYERS; p++) if (clear >> p & 1) {   // a new epoch: stop at once, no fall
            g_env[p] = 0; if (ch[p].amp) { vib_stop(ch[p].v); ch[p].v = NULL; ch[p].amp = 0; }
        }
        if (taps && dev.v && was_on) { vib_tap(&dev); tap_end = t + GAP_NS; if (phone.v == &dev && phone.amp) phone.last = t - RENEW_NS + GAP_NS; }   // a running rumble comes back after the tap
        int playing = phone.amp != 0;
        for (int p = 0; p < DM_PLAYERS; p++) if (ch[p].amp) playing = 1;
        if (!tick && !dn && !clear && !taps && !( !was_on && playing)) continue;
        if (tick) {   // the envelope: the strongest level since the last tick, or the fall
            for (int p = 0; p < DM_PLAYERS; p++) {
                float now = dm_haptics_level(st[p].motor, 2, 1.0f).intensity, want = peak[p] > now ? peak[p] : now;
                float e = g_env[p];
                e = want >= e ? want : e * FALL > want ? e * FALL : want;
                if (e < 4 / 255.0f) e = 0;
                g_env[p] = e;
            }
            next = t + TICK_NS;
        }
        for (int p = 0; p < DM_PLAYERS; p++) {   // each player: the controller that plays it
            vib_t *to = pad[p].v ? &pad[p] : NULL;
            if (!to) continue;   // no controller: the device's own body (played once, below)
            int amp = was_on ? (int)(g_env[p] * 255 + 0.5f) : 0;
            if (!to->amp && amp) amp = amp >= 64 ? 255 : 0;   // on/off motors: a strong enough rumble only
            if (amp < 10) { g_env[p] = 0; if (ch[p].amp) { vib_stop(ch[p].v); ch[p].v = NULL; ch[p].amp = 0; } continue; }   // nothing to play: the thread goes back to sleep
            if (to != ch[p].v || abs(amp - ch[p].amp) >= MOVE || t - ch[p].last >= RENEW_NS) {
                static int first = 1; if (first) { first = 0; LOG("[haptics] first rumble from the game: strength %d/255", amp); }
                if (ch[p].v != to) LOG("[haptics] player %d's rumble goes to the input device %d", p + 1, pad_id[p]);
                if (ch[p].v && ch[p].v != to && ch[p].amp) vib_stop(ch[p].v);
                vib_play(to, RUMBLE_MS, amp); ch[p].v = to; ch[p].amp = amp; ch[p].last = t;
            }
        }
        float pe = 0;   // the device's own body: the players without a controller, the strongest of them
        for (int p = 0; p < DM_PLAYERS; p++) if (!pad[p].v && g_env[p] > pe) pe = g_env[p];
        int pamp = was_on && dev.v ? (int)(pe * 255 + 0.5f) : 0;
        if (dev.v && !dev.amp && pamp) pamp = pamp >= 64 ? 255 : 0;
        if (pamp < 10) {
            for (int p = 0; p < DM_PLAYERS; p++) if (!pad[p].v) g_env[p] = 0;
            if (phone.amp) { vib_stop(phone.v); phone.v = NULL; phone.amp = 0; }
        } else if (!(dev.v && t < tap_end)) {   // a tap is still playing on the device: the next tick plays the rumble
            if (phone.v != &dev || abs(pamp - phone.amp) >= MOVE || t - phone.last >= RENEW_NS) {
                if (phone.v != &dev) LOG("[haptics] the device's own body rumbles (the players without a controller)");
                vib_play(&dev, RUMBLE_MS, pamp); phone.v = &dev; phone.amp = pamp; phone.last = t;
            }
        }
    }
    return NULL;
}
void dm_haptics_android_start(JNIEnv *E, JavaVM *vm, jobject actctx, int on) {
    // the context object (the NativeActivity, or the player's GameActivity): the haptics thread reaches it after
    // this call returns, so it goes into a global reference here (the call runs on Java's thread)
    g_vm = vm;
    g_actobj = (E && actctx) ? (*E)->NewGlobalRef(E, actctx) : NULL;
    if (E && actctx && !g_actobj && (*E)->ExceptionCheck(E)) { (*E)->ExceptionClear(E); LOG("[RED] [haptics] cannot keep the context: no vibration"); }
    atomic_store(&g_on, on != 0);
    dm_haptics_init(sink, 1);
    pthread_t t; if (!pthread_create(&t, NULL, haptics_thread, NULL)) pthread_detach(t);
}
