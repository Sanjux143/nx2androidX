"""The motion's rotation mapping: the device's frame turns into the screen's frame.

The device's sensors give the axes in the device's natural frame; the game wants the screen's frame
(x right, y up, z out of the screen). The display's rotation turns them (motion_map.h). The mapping is
pure, so it is compiled here on the host and checked (the phone cannot be turned by hand in a test).
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_cc  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
HDR = ROOT / 'nxnative/dm/android/motion_map.h'

HARNESS = r'''
#include "motion_map.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>

static int close_to(double a, double b) { return fabs(a - b) < 1e-9; }

int main(void) {
    double in[3] = {1.0, 2.0, 3.0}, out[3];

    dm_motion_map(0, in, out);                       // 0 degrees: as it is
    assert(close_to(out[0], 1.0) && close_to(out[1], 2.0) && close_to(out[2], 3.0));

    dm_motion_map(1, in, out);                       // 90 degrees: (-y, x, z)
    assert(close_to(out[0], -2.0) && close_to(out[1], 1.0) && close_to(out[2], 3.0));

    dm_motion_map(2, in, out);                       // 180 degrees: (-x, -y, z)
    assert(close_to(out[0], -1.0) && close_to(out[1], -2.0) && close_to(out[2], 3.0));

    dm_motion_map(3, in, out);                       // 270 degrees: (y, -x, z)
    assert(close_to(out[0], 2.0) && close_to(out[1], -1.0) && close_to(out[2], 3.0));

    // a rotation keeps the length and turns nothing upside down
    for (int rot = 0; rot < 4; rot++) {
        dm_motion_map(rot, in, out);
        double a = 0, b = 0;
        for (int i = 0; i < 3; i++) { a += in[i] * in[i]; b += out[i] * out[i]; }
        assert(close_to(a, b));
        // the z axis stays: the screen's face is still the screen's face
        assert(close_to(out[2], in[2]));
    }

    // the gravity in the screen's frame points down the screen, whichever way the device is held:
    // upright (gravity along -y, the display at 0 degrees) and turned a quarter (the device's -x is
    // down when the display reads 90 degrees, its +x when it reads 270)
    double up[3] = {0.0, -1.0, 0.0}, out2[3];
    dm_motion_map(0, up, out2); assert(close_to(out2[1], -1.0) && close_to(out2[0], 0.0));
    double ccw[3] = {-1.0, 0.0, 0.0};
    dm_motion_map(1, ccw, out2); assert(close_to(out2[1], -1.0) && close_to(out2[0], 0.0));
    double cw[3] = {1.0, 0.0, 0.0};
    dm_motion_map(3, cw, out2); assert(close_to(out2[1], -1.0) && close_to(out2[0], 0.0));

    printf("the motion's rotation mapping is right\n");
    return 0;
}
'''


class MotionMap(unittest.TestCase):

    @unittest.skipUnless(host_cc.posix(), 'a POSIX host C compiler is required')
    def test_the_rotation_mapping(self):
        with tempfile.TemporaryDirectory(prefix='nx2a-motion-map-') as tmp:
            c, exe = Path(tmp) / 'map.c', Path(tmp) / 'map'
            c.write_text(HARNESS)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-I', str(HDR.parent),
                            str(c), '-o', str(exe), '-lm'], check=True)
            run = subprocess.run([str(exe)], capture_output=True, text=True, timeout=15)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == '__main__':
    unittest.main()
