"""forkpool.py: the pool without POSIX semaphores. A child that fails says why, and the parent reads every child's pipe
before it stops: the failure is read once, as the reason, and not as the other children's broken pipes (the split's red line
was buried under them: the player saw the noise, not the reason)."""
import io, os, shutil, sys, tempfile, unittest
from contextlib import redirect_stderr
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'nxnative/dm/gfx/tools'
sys.path.insert(0, str(TOOLS))

from forkpool import fork_map   # noqa: E402


class Pool(unittest.TestCase):

    def test_the_results_come_back_in_the_items_order(self):
        self.assertEqual([2, 4, 6, 8, 10], fork_map(lambda x: x * 2, [1, 2, 3, 4, 5], jobs=3))

    def test_a_child_that_stops_says_why(self):
        def work(x):
            if x == 3: sys.exit('[RED] the file does not fit in this device')
            return x
        err = io.StringIO()
        with redirect_stderr(err):
            with self.assertRaises(RuntimeError) as e:
                fork_map(work, [1, 2, 3, 4, 5], jobs=3)
        self.assertIn('the file does not fit in this device', err.getvalue())
        self.assertIn('1 of the 3 forked children failed', str(e.exception))

    def test_a_child_that_raises_says_why(self):
        def work(x):
            if x == 2: raise ValueError('the shader is broken')
            return x
        err = io.StringIO()
        with redirect_stderr(err):
            with self.assertRaises(RuntimeError):
                fork_map(work, [1, 2, 3], jobs=2)
        self.assertIn('ValueError: the shader is broken', err.getvalue())

    def test_no_child_is_left_writing_into_a_pipe_nobody_reads(self):
        """A child that fails does not break the others' pipes: the parent reads every pipe before it stops"""
        def work(x):
            if x == 1: sys.exit('[RED] no')
            return x
        err = io.StringIO()
        with redirect_stderr(err):
            with self.assertRaises(RuntimeError):
                fork_map(work, [1, 2, 3], jobs=1)   # one child for all three: it fails on the first
        self.assertNotIn('BrokenPipeError', err.getvalue())


if __name__ == '__main__':
    unittest.main()
