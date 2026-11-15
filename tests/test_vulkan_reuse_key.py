# Unit tests of the Vulkan shader bundle's reuse key (python -m unittest discover tests). They need no game files, toolchains or device.
import tempfile, unittest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from porter.paths import NXNATIVE          # noqa: E402
from porter.upstream import nsp             # noqa: E402


class VulkanKey(unittest.TestCase):
    """The Vulkan bundle's reuse key: the translator's own inputs, not the split's readers. A change to the split (its reader, its tables) leaves the bundle as it is, so it must not
    cost the port the whole translation again (FINAL FANTASY RESONANCE: 73,512 shaders, 40 minutes)"""

    def repo(self):
        """A tool tree of its own: the translator, a module it imports and a module that one imports, and a reader of the split"""
        r = Path(tempfile.mkdtemp(prefix='nx2a_vk_key_'))
        for d in ('dm/android/tools', 'dm/gfx/tools', 'dm/games'): (r / d).mkdir(parents=True)
        (r / 'dm/android/tools/shader_vk.py').write_text('import dmsh_chain\n', encoding='utf-8')
        (r / 'dm/gfx/tools/dmsh_chain.py').write_text('import game\n', encoding='utf-8')
        (r / 'dm/games/game.py').write_text('# the profile reader\n', encoding='utf-8')
        (r / 'dm/gfx/tools/shader_split.py').write_text('# the split\n', encoding='utf-8')
        return r

    def names(self, r):
        return {p.name for p in nsp.vulkan_files(r)}

    def key(self, r):
        return nsp.fingerprint(('files', nsp.vulkan_files(r)))

    def test_the_imports_are_followed(self):
        r = self.repo()
        self.assertEqual(self.names(r), {'shader_vk.py', 'dmsh_chain.py', 'game.py'})

    def test_a_change_in_the_split_leaves_the_key_alone(self):
        r = self.repo()
        key = self.key(r)
        (r / 'dm/gfx/tools/shader_split.py').write_text('# the split, changed\n', encoding='utf-8')
        self.assertEqual(self.key(r), key)

    def test_a_change_in_the_translator_changes_the_key(self):
        r = self.repo()
        key = self.key(r)
        (r / 'dm/gfx/tools/dmsh_chain.py').write_text('import game\n# changed\n', encoding='utf-8')
        self.assertNotEqual(self.key(r), key)

    def test_the_repository_s_translator_inputs_are_in_the_key(self):
        names = {p.name for p in nsp.vulkan_files(NXNATIVE)}
        for n in ('shader_vk.py', 'dmsv.py', 'dmsh_facts.py', 'dmsh_chain.py', 'shader_stage.py', 'game.py'):
            self.assertIn(n, names)

    def test_the_repository_s_split_readers_are_not_in_the_key(self):
        names = {p.name for p in nsp.vulkan_files(NXNATIVE)}
        for n in ('shader_split.py', 'unityfs.py', 'uepak.py', 'forkpool.py'):
            self.assertNotIn(n, names)


if __name__ == '__main__':
    unittest.main()
