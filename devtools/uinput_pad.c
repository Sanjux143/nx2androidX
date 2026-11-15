// A virtual gamepad, so the controller's path can be tested without a controller:
// the same kernel events a pad sends (BTN_NORTH and friends), a chosen key layout
// (Android's key layout file is picked by the device's bus, vendor and product),
// the sticks and the triggers as axes.
//
// The point of the key layout: Android names a button by the device's key layout
// file, and the files differ per vendor - the Nintendo Switch Pro Controller's
// (/system/usr/keylayout/Vendor_057e_Product_2009.kl) calls the top button
// BUTTON_Y while the generic one calls it BUTTON_X. The scan code says where the
// button IS and is the same everywhere, so a controller that gets its keys
// translated by position has to be tested with both layouts.
//
// Build (the NDK's clang, for the phone's own arm64):
//   $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android24-clang -O2 -o uinput_pad uinput_pad.c
// Push and run (the shell may write /dev/uinput on a phone with no root):
//   adb push uinput_pad /data/local/tmp/uinput_pad
//   adb shell 'chmod 755 /data/local/tmp/uinput_pad'
//   adb shell '/data/local/tmp/uinput_pad "Nintendo Switch Pro Controller" 5 1406 8201 32769 st /data/local/tmp/pad.cmd'
//
//   uinput_pad <name> <bus> <vid> <pid> <version> <flags> <script>
//     flags: s = the sticks (x, y, rx, ry), z = the right stick (z, rz),
//            t = the analog triggers (ltrigger, rtrigger), g = the pedal names (gas, brake)
//   The script is a file on the device, re-read every 200 ms: every new line is run
//   (append a line to make the pad press something; a new instance replays the
//   whole file from its start).
//     key <code> <value>    (the kernel's code: 304 south, 305 east, 307 north, 308 west,
//                           310 l1, 311 r1, 312 zl, 313 zr, 314 select, 315 start, 317 l3, 318 r3)
//     axis <code> <value>   (0 x, 1 y, 2 z, 3 rx, 4 ry, 5 rz, 17 ltrigger, 18 rtrigger, 22 gas, 23 brake)
//     quit
#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

#ifndef ABS_LTRIGGER
#define ABS_LTRIGGER 0x11
#define ABS_RTRIGGER 0x12
#endif
#ifndef ABS_GAS
#define ABS_GAS 0x16
#define ABS_BRAKE 0x17
#endif

static int fd = -1;

static void emit(int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = type; e.code = code; e.value = value;
    if (write(fd, &e, sizeof e) < 0) fprintf(stderr, "write: %s\n", strerror(errno));
    e.type = EV_SYN; e.code = 0; e.value = 0;
    if (write(fd, &e, sizeof e) < 0) fprintf(stderr, "write: %s\n", strerror(errno));
}

int main(int argc, char **argv) {
    if (argc < 8) { fprintf(stderr, "usage: uinput_pad name bus vid pid version flags script\n"); return 2; }
    const char *name = argv[1];
    int bus = atoi(argv[2]), vid = atoi(argv[3]), pid = atoi(argv[4]), version = atoi(argv[5]);
    const char *flags = argv[6], *script = argv[7];

    fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { fprintf(stderr, "open /dev/uinput: %s\n", strerror(errno)); return 1; }

    // the modern API: the setup first (the name and the ids), then the bits, then the create
    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "%s", name);
    setup.id.bustype = (unsigned short)bus;
    setup.id.vendor = (unsigned short)vid;
    setup.id.product = (unsigned short)pid;
    setup.id.version = (unsigned short)version;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) { fprintf(stderr, "UI_DEV_SETUP: %s\n", strerror(errno)); return 1; }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    // the buttons: the four face ones, the shoulders, the triggers, select/start, the stick clicks
    int keys[] = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2,
                  BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (unsigned i = 0; i < sizeof keys / sizeof keys[0]; i++) ioctl(fd, UI_SET_KEYBIT, keys[i]);

    // the axes (the sticks and the triggers): each one needs its own setup before the device is made
    int want[10]; int nwant = 0;
    if (strchr(flags, 's')) { want[nwant++] = ABS_X; want[nwant++] = ABS_Y; want[nwant++] = ABS_RX; want[nwant++] = ABS_RY; }
    if (strchr(flags, 'z')) { want[nwant++] = ABS_Z; want[nwant++] = ABS_RZ; }
    if (strchr(flags, 't')) { want[nwant++] = ABS_LTRIGGER; want[nwant++] = ABS_RTRIGGER; }
    if (strchr(flags, 'g')) { want[nwant++] = ABS_GAS; want[nwant++] = ABS_BRAKE; }
    if (nwant > 0) {
        ioctl(fd, UI_SET_EVBIT, EV_ABS);
        for (int i = 0; i < nwant; i++) {
            struct uinput_abs_setup abs;
            memset(&abs, 0, sizeof abs);
            abs.code = (unsigned short)want[i];
            abs.absinfo.minimum = -32768;
            abs.absinfo.maximum = 32767;
            abs.absinfo.fuzz = 16;
            abs.absinfo.flat = 128;
            if (ioctl(fd, UI_ABS_SETUP, &abs) < 0) fprintf(stderr, "UI_ABS_SETUP %d: %s\n", want[i], strerror(errno));
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) { fprintf(stderr, "UI_DEV_CREATE: %s\n", strerror(errno)); return 1; }
    fprintf(stderr, "created %s (bus %d %04x:%04x)\n", name, bus, vid, pid);
    fflush(stderr);
    sleep(2);   // Android needs a moment to notice the device

    // the script is re-read: every new line is run, so the pad can be driven while it lives
    long offset = 0;
    for (;;) {
        FILE *f = fopen(script, "r");
        if (f) {
            if (fseek(f, offset, SEEK_SET) != 0) { fclose(f); usleep(200000); continue; }
            char line[256];
            while (fgets(line, sizeof line, f)) {
                offset += (long)strlen(line);
                char cmd[32] = {0};
                int a = 0, b = 0;
                if (sscanf(line, "%31s %d %d", cmd, &a, &b) < 1) continue;
                if (!strcmp(cmd, "key")) emit(EV_KEY, a, b);
                else if (!strcmp(cmd, "axis")) emit(EV_ABS, a, b);
                else if (!strcmp(cmd, "quit")) { fclose(f); ioctl(fd, UI_DEV_DESTROY); close(fd); return 0; }
            }
            fclose(f);
        }
        usleep(200000);
    }
}
