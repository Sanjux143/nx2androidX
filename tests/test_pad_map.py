"""The physical controller's keys: the player's own bindings, the default dictionary and the calibration.

The rules are pure C (nxnative/dm/android/pad_map.h): the phone's front end uses them, and here they are
compiled on the host and checked. The default dictionary is the Switch's layout by position: X on top, Y on
the left, A on the right, B at the bottom (the game speaks Switch positions; what the controller prints on
its buttons is only a sticker).

The position comes from the scan code, which is the same on every controller. Android's key codes only say
what the system calls a button, and that is not the same everywhere - the Nintendo controller calls the top
button BUTTON_Y - so they are only the fallback for the buttons that carry no scan code (the d-pad).
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_cc  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
HDR = ROOT / 'nxnative/dm/android/pad_map.h'
DM = ROOT / 'nxnative/dm/host'

HARNESS = r'''
#include "pad_map.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void defaults(void) {
    // By position: the scan code says where the button IS (the same on every controller).
    // The Switch's arrangement: X on top, Y on the left, A on the right, B at the bottom.
    assert(pad_default_bit(PAD_SCAN_SOUTH, 0, 0) == DM_BTN_B);
    assert(pad_default_bit(PAD_SCAN_EAST, 0, 0) == DM_BTN_A);
    assert(pad_default_bit(PAD_SCAN_NORTH, 0, 0) == DM_BTN_X);
    assert(pad_default_bit(PAD_SCAN_WEST, 0, 0) == DM_BTN_Y);
    assert(pad_default_bit(PAD_SCAN_L1, 0, 0) == DM_BTN_L);
    assert(pad_default_bit(PAD_SCAN_R1, 0, 0) == DM_BTN_R);
    assert(pad_default_bit(PAD_SCAN_L2, 0, 0) == DM_BTN_ZL);
    assert(pad_default_bit(PAD_SCAN_R2, 0, 0) == DM_BTN_ZR);
    assert(pad_default_bit(PAD_SCAN_L3, 0, 0) == DM_BTN_LS);
    assert(pad_default_bit(PAD_SCAN_R3, 0, 0) == DM_BTN_RS);
    assert(pad_default_bit(PAD_SCAN_START, 0, 0) == DM_BTN_PLUS);
    assert(pad_default_bit(PAD_SCAN_SELECT, 0, 0) == DM_BTN_MINUS);

    // The position wins over the key code's name: a controller whose key layout calls the top
    // button BUTTON_Y (the Nintendo one does) still gets X there.
    assert(pad_default_bit(PAD_SCAN_NORTH, PAD_KEY_BUTTON_Y, 0) == DM_BTN_X);
    assert(pad_default_bit(PAD_SCAN_WEST, PAD_KEY_BUTTON_X, 0) == DM_BTN_Y);
    assert(pad_default_bit(PAD_SCAN_NORTH, PAD_KEY_BUTTON_X, 0) == DM_BTN_X);
    assert(pad_default_bit(PAD_SCAN_SOUTH, PAD_KEY_BUTTON_A, 0) == DM_BTN_B);

    // No scan code (the d-pad has none): the key code's name it is.
    assert(pad_default_bit(0, PAD_KEY_DPAD_UP, 0) == DM_BTN_UP);
    assert(pad_default_bit(0, PAD_KEY_DPAD_DOWN, 0) == DM_BTN_DOWN);
    assert(pad_default_bit(0, PAD_KEY_DPAD_LEFT, 0) == DM_BTN_LEFT);
    assert(pad_default_bit(0, PAD_KEY_DPAD_RIGHT, 0) == DM_BTN_RIGHT);
    assert(pad_default_bit(0, 9999, 0) == 0);
    assert(pad_default_bit(9999, 0, 0) == 0);

    // by print (deprecated): the Xbox print - A at the bottom, B on the right, Y on top, X on the left
    assert(pad_default_bit(PAD_SCAN_SOUTH, 0, 1) == DM_BTN_A);
    assert(pad_default_bit(PAD_SCAN_EAST, 0, 1) == DM_BTN_B);
    assert(pad_default_bit(PAD_SCAN_NORTH, 0, 1) == DM_BTN_Y);
    assert(pad_default_bit(PAD_SCAN_WEST, 0, 1) == DM_BTN_X);
    assert(pad_default_bit(PAD_SCAN_L1, 0, 1) == DM_BTN_L);
    assert(pad_default_bit(PAD_SCAN_START, 0, 1) == DM_BTN_PLUS);
    assert(pad_default_bit(0, 9999, 1) == 0);
}

static void table(void) {
    pad_map_t map;
    const char *spec =
        "v1\n"
        "dev 3 labels=1 dz=0.15 tr=0.55 player=1\n"
        "bind scan:305 a\n"
        "unbind scan:314\n"
        "dev 7 labels=0 dz=0.12 tr=0.5 player=-1\n";
    assert(pad_map_parse(&map, spec) == 2);

    // the device's own table: the binding wins over the dictionary
    assert(pad_map_key_bit(&map, 3, 305, 97) == DM_BTN_A);
    // a key the player did not touch: the dictionary, by print for this device (labels=1): Y on top
    assert(pad_map_key_bit(&map, 3, 307, 99) == DM_BTN_Y);
    // a disabled source: nothing
    assert(pad_map_key_bit(&map, 3, 314, 109) == 0);
    // the other device: by position, and its own table
    assert(pad_map_key_bit(&map, 7, 305, 97) == DM_BTN_A);
    assert(pad_map_key_bit(&map, 7, 0, 96) == DM_BTN_B);
    // by position is the Switch's arrangement: X on top, Y on the left
    assert(pad_map_key_bit(&map, 7, 307, 99) == DM_BTN_X);
    assert(pad_map_key_bit(&map, 7, 308, 100) == DM_BTN_Y);
    // a device without a table: the dictionary, by position
    assert(pad_map_key_bit(&map, 9, 0, 96) == DM_BTN_B);

    // the player a device plays: the table says, a device the table does not know plays player 1
    assert(pad_map_player(&map, 3) == 1);
    assert(pad_map_player(&map, 7) == -1);   // set aside: it does nothing
    assert(pad_map_player(&map, 9) == 0);

    // the face buttons' flip: the table's, else the environment's (unset here)
    assert(pad_map_labels(&map, 3) == 1);
    assert(pad_map_labels(&map, 7) == 0);
    assert(pad_map_labels(&map, 9) == 0);

    // the calibration: the device's own, the default for the others
    assert(pad_map_deadzone(&map, 3) > 0.149f && pad_map_deadzone(&map, 3) < 0.151f);
    assert(pad_map_trigger(&map, 3) > 0.549f && pad_map_trigger(&map, 3) < 0.551f);
    assert(pad_map_deadzone(&map, 9) == PAD_DEFAULT_DEADZONE);
    assert(pad_map_trigger(&map, 9) == PAD_DEFAULT_TRIGGER);

    // an axis binding: the half of the axis, with its sign
    assert(pad_map_has_axis(&map, 3, PAD_AXIS_LTRIGGER) == 0);
    assert(pad_map_axis_bit(&map, 3, PAD_AXIS_LTRIGGER, 0.9f) == 0);
}

static void axes(void) {
    pad_map_t map;
    assert(pad_map_parse(&map, "v1\ndev 5 labels=0\nbind axis:17:+ zl\n") == 1);
    assert(pad_map_has_axis(&map, 5, PAD_AXIS_LTRIGGER) == 1);
    assert(pad_map_axis_bit(&map, 5, PAD_AXIS_LTRIGGER, 0.9f) == DM_BTN_ZL);
    assert(pad_map_axis_bit(&map, 5, PAD_AXIS_LTRIGGER, -0.9f) == 0);
}

static void broken(void) {
    pad_map_t map;
    // a broken table is a table with fewer entries, never a crash
    assert(pad_map_parse(&map, "v1\ndev notanumber\nbind\nbind scan:305 nosuchbutton\nunbind\n") >= 0);
    assert(pad_map_key_bit(&map, 0, 0, PAD_KEY_BUTTON_A) == DM_BTN_B);
}

int main(void) {
    defaults();
    table();
    axes();
    broken();
    printf("pad_map ok\n");
    return 0;
}
'''


class PadMapTest(unittest.TestCase):
    """The mapping table the gamepad center pushes to the host."""

    def test_harness_builds_and_passes(self):
        if not host_cc.posix():
            self.skipTest('no C compiler here')
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / 'harness.c'
            src.write_text(HARNESS)
            exe = Path(d) / 'harness'
            build = subprocess.run(
                ['cc', '-std=gnu11', '-I', str(HDR.parent), '-I', str(DM), '-o', str(exe), str(src)],
                capture_output=True, timeout=120)
            self.assertEqual(build.returncode, 0, build.stderr.decode('utf-8', 'replace'))
            run = subprocess.run([str(exe)], capture_output=True, timeout=60)
            self.assertEqual(run.returncode, 0, run.stderr.decode('utf-8', 'replace'))


if __name__ == '__main__':
    unittest.main()
