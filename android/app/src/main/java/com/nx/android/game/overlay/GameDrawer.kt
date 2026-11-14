package com.nx.android.game.overlay

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.data.FrameGenKnobs
import com.nx.android.data.FrameGenSettings
import com.nx.android.data.LosslessDll
import com.nx.android.data.RatePin
import com.nx.android.game.hud.HudField
import com.nx.android.game.Native
import com.nx.android.game.input.PadStyle
import com.nx.android.core.ui.components.CapsuleButton
import com.nx.android.core.ui.components.Hint
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.core.ui.theme.GlassSheet
import com.nx.android.core.ui.theme.NxPalette
import kotlinx.coroutines.delay

/** 抽屉的四个标签页（存档的导出与导入在库页的抽屉里，这里不再有） */
enum class DrawerTab(@StringRes val label: Int) {
    PERF(R.string.drawer_tab_perf), HUD(R.string.drawer_tab_hud),
    FRAMEGEN(R.string.drawer_tab_framegen), CONTROLS(R.string.drawer_tab_controls)
}

/**
 * 游戏内抽屉（:play 进程自己的 UI）：虚拟手柄的菜单按钮、物理手柄菜单键或系统返回手势唤出，
 * 玻璃卡样式与应用一致，四个标签页（性能 / HUD / 插帧 / 操作）+ 底部动作行。
 *
 * 帧率档位与帧生成即改即生效（JNI）；手柄的显示与浓淡是 App 自己的偏好（唯一真值），其余是宿主自己的设置
 * （震动、底座模式，即改即生效并存进包的 settings.txt）。宿主不再画任何面板：没有暂停菜单、
 * 没有布局编辑器、没有存档选择器、没有软键盘——游戏的文本输入走系统输入法（App 弹自己的对话框）。
 *
 * 动作行只有两个：主按钮关抽屉（游戏在暂停时顺带恢复），退出的按钮是红的。暂停游戏本身在顶部
 * 的一条动作里，点了即暂停并收起抽屉——玩家看到画面上的暂停遮罩。
 *
 * 存档不在这里——导出与导入都在库页选中游戏后的抽屉里（下次启动游戏时生效）。
 * Back 不经过这里（GameActivity 分流）。
 */
@Composable
fun GameDrawer(
    paused: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    settings: FrameGenSettings?,
    onRatePin: (RatePin) -> Unit,
    padShown: () -> Boolean,
    onPadShown: (Boolean) -> Unit,
    padOpacity: () -> Int,
    onPadOpacity: (Int) -> Unit,
    padStyle: () -> PadStyle,
    onPadStyle: (PadStyle) -> Unit,
    hudStats: () -> Boolean,
    onHudStats: (Boolean) -> Unit,
    hudDevice: () -> Boolean,
    onHudDevice: (Boolean) -> Unit,
    hudFields: () -> Set<HudField>,
    onHudFields: (Set<HudField>) -> Unit,
    hudFahrenheit: () -> Boolean,
    onHudFahrenheit: (Boolean) -> Unit,
    onLayoutEditor: () -> Unit,
    onResetPadLayout: () -> Unit,
    onPauseGame: () -> Unit,
    onResumeGame: () -> Unit,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { onOpenChange(false) }) {
                var status by remember { mutableStateOf("-1 not tried") }
                var ratePin by remember { mutableStateOf(0) }
                // 宿主有没有帧率档位这个入口：老包里的旧宿主没有，调用会抛 UnsatisfiedLinkError——
                // 那就整个不显示（玩家不该点一个按不动的开关）
                val rateSupported = remember { runCatching { Native.perfPinGet() }.isSuccess }
                LaunchedEffect(open) {   // 打开期间 2 Hz 轮询宿主的状态串与帧率档位
                    while (open) {
                        runCatching { status = Native.frameGenStatus() }
                        if (rateSupported) runCatching { ratePin = Native.perfPinGet() }
                        delay(500)
                    }
                }
                Column(
                    Modifier.align(Alignment.CenterStart).width(420.dp).fillMaxHeight().padding(vertical = 16.dp)
                        // 面板自己消费点击：点在行与行之间（或纯文本上）不至于冒泡到遮罩把抽屉关掉
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    GlassSheet(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                            var tab by remember { mutableIntStateOf(0) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((i, t) in DrawerTab.entries.withIndex()) {
                                    val selected = tab == i
                                    Box(
                                        Modifier.clip(RoundedCornerShape(50))
                                            .background(if (selected) NxPalette.Cream else Color.White.copy(alpha = 0.16f))
                                            .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) { Text(stringResource(t.label), fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            // 内容页：滚动，占据动作行之上的全部空间（动作行在本列底部，不可能被挤出）
                            Column(
                                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                when (DrawerTab.entries[tab]) {
                                    // 性能页：帧率档位与底座模式（两个都是性能取舍，与帧生成无关）
                                    DrawerTab.PERF -> PerfRows(ratePin, onRatePin, rateSupported)
                                    DrawerTab.HUD -> HudRows(hudStats, onHudStats, hudDevice, onHudDevice,
                                        hudFields, onHudFields, hudFahrenheit, onHudFahrenheit)
                                    DrawerTab.FRAMEGEN -> if (settings == null) HintRow(stringResource(R.string.drawer_no_framegen))
                                        else FrameGenRows(settings, status)
                                    DrawerTab.CONTROLS -> ControlRows(padShown, onPadShown, padOpacity, onPadOpacity,
                                        padStyle, onPadStyle, onLayoutEditor, onResetPadLayout)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            // 动作行：暂停/继续（同一个按钮，词随状态翻转）与退出。
                            // 抽屉没有自己的关闭按钮——关它靠点遮罩或系统返回手势；
                            // 暂停也不收抽屉：玩家可以一边停着一边调设置。
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CapsuleButton(
                                    if (paused) stringResource(R.string.drawer_resume_game) else stringResource(R.string.drawer_pause_game),
                                    onClick = if (paused) onResumeGame else onPauseGame,
                                    modifier = Modifier.weight(1f))
                                ExitButton(stringResource(R.string.drawer_exit), onClick = onExit, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 退出：红色描边，和主按钮一眼分得开（误触的代价不对称） */
@Composable
private fun ExitButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NxPalette.Danger),
        border = BorderStroke(1.dp, NxPalette.Danger.copy(alpha = 0.6f)),
        modifier = modifier.height(46.dp),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
private fun HintRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 分组的小标题：页里的东西按组走，一眼能扫 */
@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
         fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/** 一行：图标 + 标题 + 小字提示 + 控件 */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    title: String,
    hint: String? = null,
    control: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        control()
    }
}

/** 一行：图标 + 标题 + 小字提示 + 药丸开关（App 自己的那套）。enabled=false 时开关灰着，按不动 */
@Composable
private fun ToggleRow(
    icon: ImageVector, title: String, hint: String? = null,
    checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit,
) {
    DrawerRow(icon, title, hint) {
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .background(if (!enabled) Color.White.copy(alpha = 0.08f)
                           else if (checked) NxPalette.Ready.copy(alpha = 0.25f) else NxPalette.Unbuilt.copy(alpha = 0.25f))
                .clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 5.dp),
        ) { Text(when {
                !enabled -> stringResource(R.string.common_unavailable)
                checked -> stringResource(R.string.common_on)
                else -> stringResource(R.string.common_off)
            }, fontSize = 12.sp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    checked -> NxPalette.Ready
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }, fontWeight = FontWeight.Bold) }
    }
}

/** 一行：图标 + 标题 + 小字提示 + 展开的箭头（点了去某个地方） */
@Composable
private fun ActionRow(
    icon: ImageVector, title: String, hint: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** HUD 页：画面上的信息（性能 HUD 与设备信息线） */
@Composable
private fun HudRows(
    hudStats: () -> Boolean, onHudStats: (Boolean) -> Unit,
    hudDevice: () -> Boolean, onHudDevice: (Boolean) -> Unit,
    hudFields: () -> Set<HudField>, onHudFields: (Set<HudField>) -> Unit,
    hudFahrenheit: () -> Boolean, onHudFahrenheit: (Boolean) -> Unit,
) {
    GroupLabel(stringResource(R.string.drawer_group_info))
    var stats by remember { mutableStateOf(hudStats()) }
    var device by remember { mutableStateOf(hudDevice()) }
    var fields by remember { mutableStateOf(hudFields()) }
    var fahrenheit by remember { mutableStateOf(hudFahrenheit()) }

    ToggleRow(Icons.Filled.QueryStats, stringResource(R.string.hud_stats), stringResource(R.string.drawer_hud_stats_hint), stats) {
        stats = it; onHudStats(it)
    }
    ToggleRow(Icons.Filled.Info, stringResource(R.string.hud_device), stringResource(R.string.drawer_hud_device_hint), device) {
        device = it; onHudDevice(it)
    }
    if (stats) {
        Text(stringResource(R.string.hud_fields), style = MaterialTheme.typography.bodyMedium)
        // 字段：chips（勾上的显示，没勾上的不采样）
        for (row in HUD_FIELD_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for ((f, label) in row) {
                    val on = f in fields
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (on) NxPalette.Ready.copy(alpha = 0.25f) else NxPalette.Unbuilt.copy(alpha = 0.25f))
                            .clickable {
                                fields = if (on) fields - f else fields + f
                                onHudFields(fields)
                            }.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(stringResource(label), fontSize = 12.sp, color = if (on) NxPalette.Ready else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (HudField.TEMP in fields) {
            OptionRow(Icons.Filled.Thermostat, stringResource(R.string.hud_temp_unit), listOf("°C", "°F"), if (fahrenheit) "°F" else "°C") {
                fahrenheit = it == "°F"; onHudFahrenheit(fahrenheit)
            }
        }
    }
}

/**
 * 性能页：帧率档位（钉住呈现的那一档）与底座模式（更清晰的画面，更耗电）。
 * 宿主那边每 30 帧重读一次档位，所以改档即时生效；底座模式是宿主自己的设置，即改即生效。
 */
@Composable
private fun PerfRows(pin: Int, onPick: (RatePin) -> Unit, rateSupported: Boolean) {
    if (rateSupported) {
        GroupLabel(stringResource(R.string.drawer_group_rate))
        // 选项的词先取好：回调里不能取词
        val labels = rateLabels()
        OptionRow(Icons.Filled.Speed, stringResource(R.string.drawer_rate_row), labels, rateLabel(pin)) { name ->
            val i = labels.indexOf(name)
            onPick(RatePin(if (i >= 0) RatePin.PINS[i] else 0))
        }
        Hint(stringResource(R.string.drawer_rate_hint))
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    GroupLabel(stringResource(R.string.drawer_group_picture))
    var docked by remember { mutableStateOf(Native.menuGet(4) != 0) }
    ToggleRow(Icons.Filled.Tv, stringResource(R.string.drawer_docked), stringResource(R.string.drawer_docked_hint), docked) {
        docked = it; Native.menuSet(4, if (it) 1.0 else 0.0)
    }
}

/** 档位的选项：跟随游戏在前，后面是宿主的三档（顺序与 RatePin.PINS 一致） */
@Composable
private fun rateLabels() = listOf(stringResource(R.string.drawer_follow_game), "60", "40", "30")

/** 当前档位的词（宿主不认得的值就是自由自适应） */
@Composable
private fun rateLabel(pin: Int) =
    rateLabels().getOrNull(RatePin.indexOf(pin)) ?: stringResource(R.string.drawer_follow_game)

/** 操作页：手柄（显示、浓淡、布局）与宿主的震动 */
@Composable
private fun ControlRows(
    padShown: () -> Boolean, onPadShown: (Boolean) -> Unit,
    padOpacity: () -> Int, onPadOpacity: (Int) -> Unit,
    padStyle: () -> PadStyle, onPadStyle: (PadStyle) -> Unit,
    onLayoutEditor: () -> Unit,
    onResetPadLayout: () -> Unit,
) {
    var shown by remember { mutableStateOf(padShown()) }
    var opacity by remember { mutableStateOf(padOpacity()) }
    var style by remember { mutableStateOf(padStyle()) }
    var rumble by remember { mutableStateOf(Native.menuGet(2) != 0) }
    // 两个选项的文案（自带 / 默认）：下拉里显示的就是它们
    val packLabel = stringResource(R.string.drawer_pad_style_pack)
    val defaultLabel = stringResource(R.string.drawer_pad_style_default)

    GroupLabel(stringResource(R.string.drawer_group_pad))
    ToggleRow(Icons.Filled.SportsEsports, stringResource(R.string.drawer_virtual_pad), null, shown) { shown = it; onPadShown(it) }
    OpacityRow(opacity) { opacity = it; onPadOpacity(it) }
    // 按键风格：自带（数据包的主题贴图）或默认（app 里的默认主题包）。只换皮，布局与命中不变
    OptionRow(Icons.Filled.Palette, stringResource(R.string.drawer_pad_style), listOf(packLabel, defaultLabel),
        if (style == PadStyle.DEFAULT) defaultLabel else packLabel) { name ->
        val picked = if (name == defaultLabel) PadStyle.DEFAULT else PadStyle.PACK
        style = picked
        onPadStyle(picked)
    }
    ActionRow(Icons.Filled.Tune, stringResource(R.string.drawer_layout_editor), onClick = onLayoutEditor)
    ActionRow(Icons.Filled.RestartAlt, stringResource(R.string.drawer_pad_reset_layout), onClick = onResetPadLayout)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ToggleRow(Icons.Filled.Vibration, stringResource(R.string.drawer_rumble), null, rumble) { rumble = it; Native.menuSet(2, if (it) 1.0 else 0.0) }
}

/** 手柄浓淡：标题行带当前值，滑杆占一整行（与上面那些行一样从文字列开始） */
@Composable
private fun OpacityRow(opacity: Int, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Opacity, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.drawer_pad_opacity), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$opacity%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = opacity.toFloat(), valueRange = 10f..100f,
               onValueChange = { onChange(it.toInt()) },
               modifier = Modifier.padding(start = 30.dp))
    }
}

private fun pushGen(settings: FrameGenSettings, knobs: FrameGenKnobs) {
    settings.save(knobs)
    // Both ways go down to the host: the off has to reach it too, or the generation keeps running and the HUD
    // keeps counting the generated frames
    Native.frameGenSet(knobs.on, knobs.mult, knobs.target, knobs.flow)
}

/** 帧生成组：开关、状态、倍率、目标帧率、光流质量（即改即生效） */
@Composable
private fun FrameGenRows(settings: FrameGenSettings, status: String) {
    var knobs by remember { mutableStateOf(settings.load()) }
    val state = status.substringBefore(' ').toIntOrNull() ?: -1
    val reason = status.substringAfter(' ', "")
    val ctx = LocalContext.current
    // 没有 Lossless.dll 就根本跑不起来：别让玩家对着一个用不了的开关和一堆用不上的选项发呆
    val dllReady = remember { LosslessDll.ensure(ctx) != null }

    GroupLabel(stringResource(R.string.drawer_tab_framegen))
    ToggleRow(Icons.Filled.Bolt, stringResource(R.string.drawer_tab_framegen), null, knobs.on, enabled = dllReady) {
        knobs = knobs.copy(on = it); pushGen(settings, knobs)
    }
    if (!dllReady) {
        Text(stringResource(R.string.drawer_no_dll), style = MaterialTheme.typography.bodySmall, color = NxPalette.Danger)
        return
    }
    // 状态行：宿主的判定是唯一真源（设备能力、DLL、显示节奏都在它那边）
    Text(
        when (state) {
            1 -> stringResource(R.string.drawer_generating)
            2 -> reason.ifEmpty { stringResource(R.string.drawer_waiting_stable) }
            0 -> stringResource(R.string.drawer_off)
            -1 -> reason.ifEmpty { stringResource(R.string.drawer_device_unsupported) }
            else -> stringResource(R.string.drawer_build_without_fg)
        } + if (state == -1 && reason.contains("Lossless.dll")) stringResource(R.string.drawer_choose_dll_hint) else "",
        style = MaterialTheme.typography.bodySmall,
        color = when (state) { 1 -> NxPalette.Ready; 2 -> NxPalette.Building; -1 -> NxPalette.Danger; else -> MaterialTheme.colorScheme.onSurfaceVariant },
    )
    if (state >= 0) {
        // 选项的词先取好：回调里不能取词
        val targets = targetLabels()
        val auto = stringResource(R.string.drawer_auto)
        OptionRow(Icons.Filled.Layers, stringResource(R.string.drawer_multiplier), listOf("2x", "3x", "4x"), "${knobs.mult}x") { name ->
            knobs = knobs.copy(mult = name.removeSuffix("x").toIntOrNull() ?: 2); pushGen(settings, knobs)
        }
        OptionRow(Icons.Filled.Speed, stringResource(R.string.drawer_target_fps), targets, targetLabel(knobs.target)) { name ->
            knobs = knobs.copy(target = targets.indexOf(name).let { if (it >= 0) FrameGenKnobs.TARGETS[it] else 0 }); pushGen(settings, knobs)
        }
        OptionRow(Icons.Filled.Waves, stringResource(R.string.drawer_flow_quality), listOf(auto, "25%", "50%", "75%", "100%"), flowLabel(knobs.flow)) { name ->
            knobs = knobs.copy(flow = if (name == auto) 0 else name.removeSuffix("%").toIntOrNull() ?: 0); pushGen(settings, knobs)
        }
        Hint(stringResource(R.string.drawer_fg_hint))
    }
}

/** 性能 HUD 的字段：chips 的两行（每行四个） */
private val HUD_FIELD_ROWS = listOf(
    listOf(
        HudField.FPS to R.string.hud_field_fps,
        HudField.FRAME_TIME to R.string.hud_field_ft,
        HudField.CPU to R.string.hud_field_cpu,
        HudField.GPU to R.string.hud_field_gpu,
    ),
    listOf(
        HudField.APP_RAM to R.string.hud_field_ram,
        HudField.SYS_RAM to R.string.hud_field_mem,
        HudField.TEMP to R.string.hud_field_temp,
        HudField.POWER to R.string.hud_field_power,
    ),
)

@Composable
private fun targetLabels() = listOf(stringResource(R.string.drawer_follow_game)) + TARGET_LABELS

@Composable
private fun targetLabel(t: Int) =
    targetLabels().getOrNull(FrameGenKnobs.TARGETS.indexOf(t)) ?: stringResource(R.string.drawer_follow_game)

@Composable
private fun flowLabel(f: Int) = if (f == 0) stringResource(R.string.drawer_auto) else "$f%"

@Composable
private fun OptionRow(icon: ImageVector, label: String, options: List<String>, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DrawerRow(icon, label) {
        Box {
            // 与库页的字段同一做法：按钮定宽，箭头钉在右端，几行的箭头排成一列
            QuietButton(current, onClick = { open = !open },
                modifier = Modifier.width(140.dp),
                trailing = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp)) })
            androidx.compose.material3.DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.width(140.dp),
            ) {
                for (o in options.distinct()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(o, fontWeight = if (o == current) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onPick(o) },
                    )
                }
            }
        }
    }
}

private val TARGET_LABELS = listOf("60", "90", "120")
