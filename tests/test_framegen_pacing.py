"""The frame generation's pacing: the window's frame rate vote, the pacer's plan, and the adaptive rate's judgement.

Three pieces, each exercised without a device:

- the window's rate vote (gfx_vk_present.c): the frame generation's output rate while it is on, the game's own rate while it is off;
  a higher rate is asked for at once, a lower one only after the current one held.
- the pacer (lsfg/lsfg_pacer.c, the real source): the multiplier's plan, the target rate's plan, and the floor that holds the generation
  back when the game's own rate falls under it.
- the adaptive frame rate's judgement (perf_android.c): the wait for the present thread is work at 60 fps, but with the frame generation on it is
  a pace (the present thread presents more images per game frame by design).
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PRESENT = ROOT / 'nxnative/dm/android/gfx_vk_present.c'
PACER = ROOT / 'nxnative/dm/android/lsfg/lsfg_pacer.c'
PACER_DIR = ROOT / 'nxnative/dm/android/lsfg'
PERF = ROOT / 'nxnative/dm/android/perf_android.c'


def compiler():
    return shutil.which('cc') or shutil.which('clang')


class WindowRate(unittest.TestCase):
    """The rate the window asks the system for (gfx_vk_present.c)."""

    def test_rate_and_vote(self):
        cc = compiler()
        if not cc:
            self.skipTest('a C compiler is needed for the window rate check')
        src = PRESENT.read_text(encoding='utf-8')
        block = src[src.index('// ---- the frame generation\'s state'):src.index('// ---- the performance HUD\'s counters')]
        harness = r'''
#include <assert.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>

''' + block + r'''
int main(void) {
    (void)g_refresh_polls;
    // the frame generation is off: the window asks for the game's own rate
    assert(window_fps(1.0 / 60.0) == 60.0);
    assert(window_fps(1.0 / 30.0) == 30.0);
    assert(window_fps(0.0) == 60.0);          // no period: the default

    // the frame generation is on: the output rate (2x at 60 fps -> 120)
    atomic_store(&g_fg_out_ns, 8333333ull);
    assert(window_fps(1.0 / 60.0) > 119.9 && window_fps(1.0 / 60.0) < 120.1);
    atomic_store(&g_fg_out_ns, 11111111ull);  // the target rate 90
    assert(window_fps(1.0 / 60.0) > 89.9 && window_fps(1.0 / 60.0) < 90.1);
    atomic_store(&g_fg_out_ns, 0);

    // the vote: a higher rate at once, a lower one after the current one held for 60 presents
    double rate = 0, want = 0; uint64_t since = 0;
    assert(window_rate_changed(60.0, &rate, &want, &since, 100) == 1);   // the first vote
    assert(rate == 60.0);
    assert(window_rate_changed(60.0, &rate, &want, &since, 101) == 0);   // the same rate: no vote
    assert(window_rate_changed(120.0, &rate, &want, &since, 102) == 1);  // higher: at once
    assert(rate == 120.0);
    assert(window_rate_changed(60.0, &rate, &want, &since, 103) == 0);   // lower: not yet
    assert(window_rate_changed(60.0, &rate, &want, &since, 150) == 0);   // still not (47 presents)
    assert(window_rate_changed(60.0, &rate, &want, &since, 163) == 1);   // 60 presents later
    assert(rate == 60.0);

    // the game that switches its own present interval (60 <-> 30) does not make the window's rate flap: the rate that is asked for does not change with it
    rate = 120.0; want = 120.0; since = 0;
    for (uint64_t i = 0; i < 200; i++) {
        assert(window_rate_changed(120.0, &rate, &want, &since, i) == 0);
        assert(rate == 120.0);
    }
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='nx2a_fg_window_') as tmp:
            c = Path(tmp) / 'check.c'
            exe = Path(tmp) / 'check'
            c.write_text(harness, encoding='utf-8')
            result = subprocess.run([cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-pthread', str(c), '-lm', '-o', str(exe)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class Pacer(unittest.TestCase):
    """The plan the pacer makes (lsfg/lsfg_pacer.c, the real source)."""

    def test_plan_and_floor(self):
        cc = compiler()
        if not cc:
            self.skipTest('a C compiler is needed for the pacer check')
        harness = r'''
#include <assert.h>
#include <stdio.h>
#include "lsfg/lsfg.h"

static void run(lsfg_pacer *p, int frames, double step, size_t *last) {
    for (int i = 0; i < frames; i++) {
        lsfg_plan pl = lsfg_pacer_plan(p, 3, 2, 0, p->last_frame ? p->last_frame + step : 100.0);
        *last = pl.generations;
    }
}

int main(void) {
    // the multiplier (2x) with the game at 60 fps: one generated frame per game frame
    lsfg_pacer p; lsfg_pacer_reset(&p);
    size_t gen = 0;
    run(&p, 1, 1.0 / 60.0, &gen);
    assert(gen == 0);                      // the first frame: the rate is not known yet
    run(&p, 5, 1.0 / 60.0, &gen);
    assert(gen == 1);                      // one generated frame per game frame
    assert(p.unloaded_base_rate > 55.0f && p.unloaded_base_rate < 65.0f);

    // the target rate the game already reaches (60 at 60): nothing to add
    lsfg_pacer_reset(&p);
    double t = 100.0;
    for (int i = 0; i < 60; i++) {
        lsfg_plan pl = lsfg_pacer_plan(&p, 3, 2, 60, t);
        t += 1.0 / 60.0;
        assert(pl.generations == 0);
    }

    // the target rate above the game's own (60 at 30): it probes and then generates
    lsfg_pacer_reset(&p);
    t = 100.0;
    lsfg_plan pl = {0, 0};
    for (int i = 0; i < 240; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 60, t); t += 1.0 / 30.0; }
    assert(pl.generations == 1);           // one generated frame per game frame: 30 + 30 = 60 fps on the screen

    // the generation's own cost: the game runs at 60 without it and collapses to 20 with it -> the generation is held back
    // (a second under the floor, then the back-off) and comes back when the game does
    lsfg_pacer_reset(&p);
    p.min_base = 30.0f;
    t = 100.0;
    for (int i = 0; i < 20; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 60.0; }
    assert(pl.generations == 1);            // the generation runs while the game keeps its rate
    for (int i = 0; i < 10; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 20.0; }
    assert(pl.generations == 1);            // half a second under the floor: it still generates (one slow frame does not stop it)
    for (int i = 0; i < 60; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 20.0; }
    assert(pl.generations == 0);            // a second under: the generation is held back, the game runs at its own rate
    assert(p.unloaded_base_rate > 50.0f);   // the reference (the game without the generation) did not follow it down
    for (int i = 0; i < 400; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 60.0; }
    assert(pl.generations == 1);            // the back-off is over and the game is at its own rate: the generation is back

    // decoupled (the game's speed follows its measured rate, so a slower base does not slow it down): the floor is what holds it back
    lsfg_pacer_reset(&p);
    p.decoupled = 1; p.min_base = 30.0f;
    t = 100.0;
    for (int i = 0; i < 20; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 60.0; }
    assert(pl.generations == 1);            // the generation runs while the game stays above the floor
    for (int i = 0; i < 90; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 20.0; }
    assert(pl.generations == 0);            // under the floor for a second: the generation is held back

    // the mode change (the drawer's live switch): the multiplier's limit does not carry over to the target rate
    lsfg_pacer_reset(&p);
    t = 100.0;
    for (int i = 0; i < 20; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 0, t); t += 1.0 / 60.0; }
    assert(pl.generations == 1);           // the multiplier: one generated frame per game frame
    for (int i = 0; i < 20; i++) { pl = lsfg_pacer_plan(&p, 3, 2, 60, t); t += 1.0 / 60.0; }
    assert(pl.generations == 0);           // the target rate the game already reaches: nothing to add
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='nx2a_fg_pacer_') as tmp:
            c = Path(tmp) / 'check.c'
            exe = Path(tmp) / 'check'
            c.write_text(harness, encoding='utf-8')
            result = subprocess.run([cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-pthread',
                                     '-I', str(PACER_DIR.parent), str(c), str(PACER), '-lm', '-o', str(exe)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class AdaptivePace(unittest.TestCase):
    """The adaptive frame rate's judgement of the wait for the present thread (perf_android.c)."""

    def test_wait_is_a_pace_with_the_frame_generation(self):
        cc = compiler()
        if not cc:
            self.skipTest('a C compiler is needed for the adaptive rate check')
        src = PERF.read_text(encoding='utf-8')
        block = src[src.index('static const int64_t STEPS[]'):src.index('static int64_t work_pct(int q)')]
        harness = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>

''' + block + r'''
int main(void) {
    (void)STEPS;
    (void)g_adapt; (void)g_work_hist; (void)g_nwork; (void)g_last_change; (void)g_last_up; (void)g_up_hold;
    (void)g_ok_frames; (void)g_game_period;
    g_step = 0;
#if FG_IMAGES > 1
    assert(dm_perf_pq_pace(5000000) == 5000000);   // 60 fps with the frame generation on: the wait is a pace
#else
    assert(dm_perf_pq_pace(5000000) == 0);         // 60 fps: the wait is work (the display holding the frames back)
#endif
    g_step = 1;
    assert(dm_perf_pq_pace(5000000) == 5000000);   // 40 fps: it is the longer period's pace
    g_step = 0;
#if FG_IMAGES > 1
    assert(dm_perf_pq_pace(5000000) == 5000000);
#else
    assert(dm_perf_pq_pace(5000000) == 0);
#endif
    return 0;
}
'''
        fg = r'''
int vk_fg_images(void) { return FG_IMAGES; }   // the images per game frame (1 = the game alone)
'''
        with tempfile.TemporaryDirectory(prefix='nx2a_fg_pace_') as tmp:
            c = Path(tmp) / 'check.c'
            fgc = Path(tmp) / 'fg.c'
            exe = Path(tmp) / 'check'
            c.write_text(harness, encoding='utf-8')
            fgc.write_text(fg, encoding='utf-8')
            for images in (1, 2):
                result = subprocess.run([cc, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-pthread',
                                         f'-DFG_IMAGES={images}', str(c), str(fgc), '-lm', '-o', str(exe)],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                result = subprocess.run([str(exe)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
