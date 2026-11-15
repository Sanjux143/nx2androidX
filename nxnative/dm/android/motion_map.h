// The device's motion in the screen's frame.
//
// Android gives the sensors in the device's natural frame (x to the right, y to the top edge, z out of the
// screen); the game wants the screen's frame (x to the right, y up, z out of the screen), so the display's
// rotation turns the axes. The same mapping as yuzu's Android front end (EmulationActivity): 90 degrees =
// (-y, x, z), 180 = (-x, -y, z), 270 = (y, -x, z); z stays where it is.
//
// Pure: the test compiles this header on its own (tests/test_motion_map.py).
#ifndef DM_MOTION_MAP_H
#define DM_MOTION_MAP_H

/** in: the device's frame; out: the screen's frame. rot: 0..3 (0, 90, 180, 270 degrees). */
static inline void dm_motion_map(int rot, const double in[3], double out[3]) {
    const double x = in[0], y = in[1];
    switch (rot & 3) {
    case 1:  out[0] = -y; out[1] =  x; break;   // 90 degrees
    case 2:  out[0] = -x; out[1] = -y; break;   // 180 degrees
    case 3:  out[0] =  y; out[1] = -x; break;   // 270 degrees
    default: out[0] =  x; out[1] =  y; break;   // 0 degrees
    }
    out[2] = in[2];
}

#endif
