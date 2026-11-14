package com.nx.android.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nx.android.data.GameIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 游戏封面：降采样读一张图，主线程之外（capyps5 rememberImage 的手法）。
 *
 * 库页的头图与「存储」分区的缩略图都用它。
 *
 * 读失败要再试：同一个路径只读一次，读不出来就一直是空的（卡片只剩一个字的占位）。
 * 文件正在被换（重装 App 后数据在恢复、运行库更新在重写包）时正是这种情况，所以再试几次。
 */
@Composable
fun rememberGameIcon(path: String?, maxSize: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, path) {
        if (path.isNullOrEmpty()) return@produceState
        for (attempt in 0 until 3) {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { GameIcon.decode(path, maxSize)?.asImageBitmap() }.getOrNull()
            }
            if (bitmap != null) {
                value = bitmap
                return@produceState
            }
            if (attempt < 2) delay(1000)   // the file may be on its way (a restore, a rewrite)
        }
    }
