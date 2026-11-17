package com.nx.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 游戏文件放在网盘目录里：这种文件没下载完时会被读成一段一段，移植前要提醒玩家 */
class GameSourceTest {

    @Test
    fun `a file in a netdisk folder is named`() {
        assertEquals("/QuarkDownloads/CloudDrive",
            GameSource.cloudFolder("/storage/emulated/0/Download/QuarkDownloads/CloudDrive/abc/歧路旅人0/x.xci"))
        assertEquals("/BaiduNetdiskDownload",
            GameSource.cloudFolder("/storage/emulated/0/Download/BaiduNetdiskDownload/game.nsp"))
    }

    @Test
    fun `an ordinary folder is not one`() {
        assertNull(GameSource.cloudFolder("/storage/emulated/0/Download/game.nsp"))
        assertNull(GameSource.cloudFolder("/storage/emulated/0/Download/GameCloud/game.nsp"))
    }
}
