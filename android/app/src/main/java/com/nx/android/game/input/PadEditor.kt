package com.nx.android.game.input

import com.nx.android.game.input.PadView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import kotlin.math.roundToInt

/**
 * 布局编辑器（App 自己的）：画在游戏画面上，照 Lemon 模拟器那套做法——
 *
 * - 拖动一个控件 = 移动它，松手就存进数据包的 settings.txt（没有「保存」按钮）
 * - 点一下一个控件（没拖动）= 弹它的大小对话框：名字、当前的大小、滑杆（0.5–2.0，步进 0.05）、
 *   [重置][取消][确定]。滑杆拖着的时候画面上就跟着变，确定才存，取消就恢复
 * - 长按一个控件（按住不动）= 把它藏起来：对局里它不出现、那片触摸归游戏。
 *   藏起来的键在编辑器里画成半透明的幽灵（虚线框 + 名字），点一下就回来
 * - 没有工具条、没有 −/+、没有网格、没有捏合、没有「放弃这些改动？」
 * - 右上角一个「完成」浮钮退出编辑
 *
 * 菜单键藏不掉（它是抽屉入口）。
 *
 * 改动的数据都在 PadView 那里（它画，也存盘）：这里只管手势与对话框。
 */
@Composable
fun PadEditor(
    controls: () -> List<PadControl>,   // 当前的控件表（编辑器改了它就变）
    onDrag: (Int, Float, Float) -> Unit,   // 拖动中：控件下标、新的中心（窗口高度为单位）
    onDrop: (Int, Float, Float) -> Unit,   // 松手：存盘
    onSizePreview: (Int, Float) -> Unit,   // 大小对话框：拖着滑杆时的预览
    onSizeCommit: (Int, Float) -> Unit,    // 大小对话框：确定，存盘
    onSizeCancel: (Int) -> Unit,           // 大小对话框：取消，恢复
    onLongPress: (Int) -> Unit,            // 长按一个控件：把它藏起来
    onShow: (Int) -> Unit,                 // 点一下半透明的幽灵：把它找回来
    onDone: () -> Unit,                    // 退出编辑
) {
    var dialogCtl by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxSize()) {
        // 触摸：拖动是移动，点按是改大小，长按是藏起来
        Box(
            Modifier.fillMaxSize().pointerInput(dialogCtl) {
                if (dialogCtl >= 0) return@pointerInput   // 大小对话框开着：别抢它的触摸
                awaitEachGesture {
                    val list = controls()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val H = size.height.toFloat()
                    val W = size.width.toFloat()
                    val c = PadHit.at(list, down.position.x, down.position.y, H)
                    if (c < 0) return@awaitEachGesture
                    val ctl = list[c]
                    val offX = ctl.cx * H - down.position.x   // 手指按在控件的哪儿：拖动时它跟着走
                    val offY = ctl.cy * H - down.position.y
                    val aspect = if (H > 0f) W / H else 1f
                    val slop = H * DRAG_SLOP
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    var moved = false
                    var longPressed = false
                    var cx = ctl.cx
                    var cy = ctl.cy
                    var last = down.uptimeMillis
                    while (true) {
                        // 手指一直没动：等到了长按的时间就藏起来（动了就是拖动，不藏）
                        val ev = if (moved || longPressed) awaitPointerEvent()
                                 else withTimeoutOrNull((longPressMs - (last - down.uptimeMillis)).coerceAtLeast(1L)) { awaitPointerEvent() }
                        if (ev == null) {
                            longPressed = true
                            onLongPress(c)
                            continue
                        }
                        val p = ev.changes.firstOrNull { it.pressed } ?: break   // 手指都抬起来了
                        last = ev.changes.first().uptimeMillis
                        val dx = p.position.x - down.position.x
                        val dy = p.position.y - down.position.y
                        if (!moved && (dx * dx + dy * dy) > slop * slop) moved = true
                        if (moved && !longPressed) {
                            cx = padDragPos((p.position.x + offX) / H, ctl.hw, aspect)
                            cy = padDragPos((p.position.y + offY) / H, ctl.hh, 1f)
                            onDrag(c, cx, cy)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                    when {
                        longPressed -> Unit          // 这次手势已经被长按吃掉了（藏起来了）
                        moved -> onDrop(c, cx, cy)
                        ctl.hidden -> onShow(c)      // 点一下幽灵：把它找回来
                        else -> dialogCtl = c        // 点按：改大小
                    }
                }
            }
        )

        // 右上角：完成（退出编辑）
        Box(
            Modifier.align(Alignment.TopEnd).padding(14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable { onDone() }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(stringResource(R.string.editor_done), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // 左上角：一行提示（拖动是移动，点按是改大小）
        Text(
            stringResource(R.string.editor_hint),
            Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 16.dp),
            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
        )

        // 大小对话框：名字、当前的大小、滑杆、[重置][取消][确定]
        if (dialogCtl >= 0) {
            val c = controls().getOrNull(dialogCtl)
            if (c != null) {
                var v by remember(dialogCtl) { mutableFloatStateOf(c.s) }
                AlertDialog(
                    onDismissRequest = { onSizeCancel(dialogCtl); dialogCtl = -1 },
                    title = { Text(padControlName(c)) },
                    text = {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(formatScale(v), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = v,
                                onValueChange = { v = it; onSizePreview(dialogCtl, it) },
                                valueRange = PAD_SIZE_MIN..PAD_SIZE_MAX,
                                steps = SCALE_STEPS,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { onSizeCommit(dialogCtl, v); dialogCtl = -1 }) {
                            Text(stringResource(R.string.common_ok))
                        }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = { v = 1f; onSizePreview(dialogCtl, 1f) }) {
                                Text(stringResource(R.string.editor_reset_layout))
                            }
                            TextButton(onClick = { onSizeCancel(dialogCtl); dialogCtl = -1 }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 手指按在控件上又走了这么远（窗口高度的比例），就是拖动而不是点按 */
private const val DRAG_SLOP = 0.02f

/** 滑杆的步进：0.5–2.0，每档 0.05（steps 是档数减一） */
private const val SCALE_STEPS = 29

/** 大小的字样：1.0x（落在两档中间时是 1.05x） */
private fun formatScale(v: Float): String {
    val steps = (v * 20f).roundToInt()
    return (if (steps % 2 == 0) String.format("%.1f", v) else String.format("%.2f", v)) + "x"
}

/** 控件的名字（对话框的标题用）：摇杆、十字键、菜单键，别的用它的标签 */
@Composable
fun padControlName(c: PadControl): String = padControlLabel(c, LocalContext.current)
