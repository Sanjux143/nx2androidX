"""数据包内嵌档案：候选顺序（包内第一、dm/games 不再参与）与重建成功后的回写。

只依赖 porter.runtime_update 的纯逻辑（候选生成、嵌入回写、指纹兜底跳过），
不跑真正的编译。"""
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


class PackProfileTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='nx2a-pack-profile-'))
        self.pack = self.tmp / 'Game-data'
        self.tid = '01001300232F4000'
        self._env = {}
        for key, value in (('NX2A_WORK', str(self.tmp / 'work')),
                           ('NX2A_HOME', str(self.tmp / 'home'))):
            self._env[key] = os.environ.get(key)
            os.environ[key] = value

    def tearDown(self):
        for key, value in self._env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def test_candidates_put_the_pack_first_and_drop_dm_games(self):
        from porter.runtime_update import profile_candidates
        cands = list(profile_candidates(self.pack, self.tid))
        # 包内第一
        self.assertEqual(cands[0], self.pack / 'profile' / self.tid)
        # 工作区三份在后
        build = self.tmp / 'work' / f'{self.tid}_build'
        self.assertEqual(cands[1:4], [build / name / self.tid
                                      for name in ('profile-mem', 'profile-derived', 'profile')])
        # 导入层兜底
        self.assertEqual(str(cands[4]), str(os.environ['NX2A_HOME'] and
                                            Path(os.environ['NX2A_HOME']) / 'profiles' / self.tid))
        # dm/games 不再参与
        self.assertTrue(all('dm' not in c.parts and 'games' not in c.parts for c in cands))

    def test_embed_copies_the_whole_profile_directory(self):
        from porter.runtime_update import embed_profile
        used = self.tmp / 'used' / self.tid
        used.mkdir(parents=True)
        (used / 'game.tsv').write_text('game rows', encoding='utf-8')
        (used / 'android.tsv').write_text('android rows', encoding='utf-8')
        (used / 'shader_map.tsv').write_text('shader map rows', encoding='utf-8')
        self.assertTrue(embed_profile(self.pack, self.tid, used))
        target = self.pack / 'profile' / self.tid
        self.assertEqual((target / 'game.tsv').read_text(encoding='utf-8'), 'game rows')
        self.assertEqual((target / 'android.tsv').read_text(encoding='utf-8'), 'android rows')
        # 档案的其它数据表（映射表等）一并进包——指纹按目录整体计算，缺文件会让重建误判漂移
        self.assertEqual((target / 'shader_map.tsv').read_text(encoding='utf-8'), 'shader map rows')
        # 副本语义：来源工作区的档案保留原位（不被移走）
        self.assertTrue((used / 'game.tsv').is_file())
        self.assertTrue((used / 'android.tsv').is_file())
        self.assertTrue((used / 'shader_map.tsv').is_file())

    def test_embed_is_a_noop_when_the_profile_is_already_the_packs(self):
        from porter.runtime_update import embed_profile
        same = self.pack / 'profile' / self.tid
        same.mkdir(parents=True)
        (same / 'game.tsv').write_text('original', encoding='utf-8')
        self.assertFalse(embed_profile(self.pack, self.tid, same))
        self.assertEqual((same / 'game.tsv').read_text(encoding='utf-8'), 'original')

    def test_embed_failure_is_reported_not_raised(self):
        from porter.runtime_update import embed_profile
        used = self.tmp / 'used2' / self.tid
        used.mkdir(parents=True)
        (used / 'game.tsv').write_text('x', encoding='utf-8')
        # pack/profile 已被一个同名文件占住：mkdir/写都会失败
        self.pack.mkdir(parents=True)
        (self.pack / 'profile').write_text('blocked', encoding='utf-8')
        self.assertFalse(embed_profile(self.pack, self.tid, used))

    def test_an_unreadable_pack_copy_falls_through_to_the_next_candidate(self):
        from porter import runtime_update as ru
        pack_copy = self.pack / 'profile' / self.tid
        pack_copy.mkdir(parents=True)
        (pack_copy / 'game.tsv').write_text('broken', encoding='utf-8')
        layer = Path(os.environ['NX2A_HOME']) / 'profiles' / self.tid
        layer.mkdir(parents=True)
        # 顺序：包内(读不懂→None)、工作区三份(不存在→None)、导入层(可读→指纹)
        fingerprints = [None, None, None, None, 'abc123']
        with patch.object(ru, 'profile_fingerprint', side_effect=fingerprints):
            candidate, fingerprint, found = ru.find_profile(self.pack, self.tid, expected='abc123')
        self.assertEqual(candidate, layer)
        self.assertEqual(fingerprint, 'abc123')
        self.assertTrue(found)


if __name__ == '__main__':
    unittest.main()
