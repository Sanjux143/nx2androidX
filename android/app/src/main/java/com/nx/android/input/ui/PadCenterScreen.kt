package com.nx.android.input.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.input.GamepadScan
import com.nx.android.input.PadCalibration
import com.nx.android.input.PadMapping
import com.nx.android.input.PadAssignment
import com.nx.android.input.PadTestInput
import com.nx.android.input.PadTestState

/**
 * 手柄中心：接在手机上的物理手柄看得见、摸得着。
 *
 * 布局照 fexdroid 的中心：左边是手柄的列表，右边是它此刻的样子（按键、摇杆、扳机），
 * 改键在左边那个按钮后面（另开一页）。
 *
 * 开着的时候游戏照常跑，但手柄的输入静音（进不了游戏）—— 面板上显示的正是被静音的那些输入。
 * 面板是 App 自己的 Compose 覆盖层，不是宿主的暂停菜单。
 */
@Composable
fun PadCenterScreen(
    controller: PadCenterController,
    onClose: () -> Unit,
    onVibrate: ((GamepadScan.Info) -> Unit)? = null,
    assignment: PadAssignment = PadAssignment(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val devices by GamepadScan.devices.collectAsState()
    val vibrate: (GamepadScan.Info) -> Unit = onVibrate ?: { info -> vibratePad(context, info) }
    val states by PadTestInput.states.collectAsState()
    var mappingOpen by remember { mutableStateOf(false) }

    // 面板开着的时候才看手柄的输入；关掉就放手
    DisposableEffect(Unit) {
        PadTestInput.start()
        onDispose { PadTestInput.stop() }
    }
    // 面板开着的时候重扫一遍：列表可能落后于系统（手柄刚重连，设备 id 变了）
    LaunchedEffect(Unit) { GamepadScan.refreshNow(context) }
    LaunchedEffect(devices) { controller.devicesChanged(devices) }
    LaunchedEffect(mappingOpen) { if (!mappingOpen) controller.cancelCapture() }

    BackHandler(enabled = true) {
        if (mappingOpen) mappingOpen = false else onClose()
    }

    val selected = devices.firstOrNull { it.id == controller.selectedId }
        ?: devices.firstOrNull()

    BoxWithConstraints(
        modifier.fillMaxSize().background(NxPalette.Background).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxWidth < 760.dp
        val sidePadding = if (maxWidth < 600.dp) 14.dp else 28.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = sidePadding).widthIn(max = 1040.dp)
                .align(Alignment.Center),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (mappingOpen) mappingOpen = false else onClose() }) {
                    Icon(
                        if (mappingOpen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                        stringResource(R.string.common_close),
                        tint = Color.White,
                    )
                }
                Text(
                    if (mappingOpen) stringResource(R.string.pad_center_mapping)
                    else stringResource(R.string.pad_center_physical),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (mappingOpen) Color.White else NxPalette.Brand,
                    modifier = Modifier.weight(1f),
                )
            }

            if (mappingOpen) {
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    PadMappingPanel(controller = controller, deviceId = selected?.id)
                }
            } else {
                if (controller.recovered) {
                    Text(
                        stringResource(R.string.pad_center_recovered),
                        style = MaterialTheme.typography.bodySmall,
                        color = NxPalette.Building,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    )
                }
                PhysicalControllerPage(
                    controller = controller,
                    devices = devices,
                    selected = selected,
                    state = selected?.let { states[it.id] },
                    compact = compact,
                    assignment = assignment,
                    onOpenMapping = { mappingOpen = true },
                    onVibrate = vibrate,
                )
            }
        }
    }
}

/** 右页：选中的那只手柄此刻的样子，加上这台机器自己的设置。 */
@Composable
private fun ColumnScope.PhysicalControllerPage(
    controller: PadCenterController,
    devices: List<GamepadScan.Info>,
    selected: GamepadScan.Info?,
    state: PadTestState?,
    compact: Boolean,
    assignment: PadAssignment,
    onOpenMapping: () -> Unit,
    onVibrate: (GamepadScan.Info) -> Unit,
) {
    val context = LocalContext.current
    if (devices.isEmpty()) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.SportsEsports, null, Modifier.size(42.dp), tint = NxPalette.Unbuilt)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pad_center_none),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { openBluetoothSettings(context) }) {
                Text(stringResource(R.string.pad_center_bluetooth))
            }
        }
        return
    }

    // 面键的四格：每格是**那个方位**的游戏键（按位置还是按标签，玩家自己定）
    val faceSlots = selected?.let { info ->
        FaceSlots(
            top = controller.resolveTarget(info.id, PadMapping.Face.TOP, PadMapping.Face.keyCodeOf(PadMapping.Face.TOP)),
            left = controller.resolveTarget(info.id, PadMapping.Face.LEFT, PadMapping.Face.keyCodeOf(PadMapping.Face.LEFT)),
            right = controller.resolveTarget(info.id, PadMapping.Face.RIGHT, PadMapping.Face.keyCodeOf(PadMapping.Face.RIGHT)),
            bottom = controller.resolveTarget(info.id, PadMapping.Face.BOTTOM, PadMapping.Face.keyCodeOf(PadMapping.Face.BOTTOM)),
        )
    } ?: FaceSlots(null, null, null, null)

    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // 左：手柄的列表，下面是「改键」
        Column(
            Modifier.width(if (compact) 190.dp else 240.dp).fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            val repeatedNames = devices.groupingBy { it.name }.eachCount()
            devices.forEach { device ->
                val active = selected?.id == device.id
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) NxPalette.Surface else Color.Transparent)
                        .clickable { controller.select(device.id) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (active) Color.White else Color(0xFFA9A9B2),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // 谁玩谁：这只控制器当几号玩家（指派是每个游戏一份，这里看的是正在跑的那个游戏的）
                        val player = assignment.playerOf(device.profileKey)
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(if (player < 0) NxPalette.Unbuilt.copy(alpha = 0.16f) else NxPalette.Brand.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                if (player < 0) stringResource(R.string.pad_center_idle)
                                else stringResource(R.string.pad_center_player, player + 1),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (player < 0) NxPalette.Unbuilt else NxPalette.Brand,
                                maxLines = 1,
                            )
                        }
                    }
                    if ((repeatedNames[device.name] ?: 0) > 1) {
                        Text(
                            stringResource(R.string.pad_center_custom_device, device.id),
                            style = MaterialTheme.typography.bodySmall,
                            color = NxPalette.Unbuilt,
                            maxLines = 1,
                        )
                    }
                    Text(
                        stringResource(
                            if (device.vibration) R.string.pad_center_vibration
                            else R.string.pad_center_no_vibration,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NxPalette.Unbuilt,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenMapping,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.pad_center_mapping),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(1.dp).fillMaxHeight().background(NxPalette.Surface))
        // 右：此刻的样子
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = if (compact) 14.dp else 28.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        selected?.name.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            if ((state?.eventCount ?: 0L) > 0L) R.string.pad_center_receiving
                            else R.string.pad_center_waiting,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if ((state?.eventCount ?: 0L) > 0L) NxPalette.Ready else Color(0xFFA9A9B2),
                    )
                    if (selected?.requiresProfileConfirmation == true) {
                        Text(
                            stringResource(R.string.pad_center_temporary_profile),
                            style = MaterialTheme.typography.bodySmall,
                            color = NxPalette.Building,
                        )
                    }
                }
                Text(
                    stringResource(R.string.pad_center_test_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxPalette.Unbuilt,
                )
            }
            Spacer(Modifier.height(24.dp))
            UtilityButtons(state?.pressed.orEmpty())
            Spacer(Modifier.height(16.dp))
            ControllerDiagram(state, compact, faceSlots)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(
                    if (selected?.axes.isNullOrEmpty()) R.string.pad_center_no_axes
                    else R.string.pad_center_test_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NxPalette.Unbuilt,
            )

            if (selected != null) {
                val profile = controller.profile(selected)
                Spacer(Modifier.height(20.dp))
                // 面键：按哪颗实体键来，由改键页里的绑定说了算（这里只做校准与振动）
                Text(
                    stringResource(R.string.pad_center_calibration),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFA9A9B2),
                )
                CalibrationSlider(
                    stringResource(R.string.pad_center_deadzone),
                    profile.calibration.deadzone,
                    PadCalibration.DEADZONE_RANGE,
                ) { value -> controller.setCalibration(selected, profile.calibration.copy(deadzone = value)) }
                CalibrationSlider(
                    stringResource(R.string.pad_center_trigger),
                    profile.calibration.trigger,
                    PadCalibration.TRIGGER_RANGE,
                ) { value -> controller.setCalibration(selected, profile.calibration.copy(trigger = value)) }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onVibrate(selected) }, shape = RoundedCornerShape(6.dp)) {
                    Icon(Icons.Filled.Vibration, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pad_center_vibrate))
                }
            }
        }
    }
}

/** 肩键与摇杆：ZL、L、L3、SELECT、OPTION、R3、R、ZR。 */
@Composable
private fun UtilityButtons(pressed: Set<String>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "ZL" to "zl",
            "L" to "l",
            "L3" to "ls",
            "-" to "minus",
            "+" to "plus",
            "R3" to "rs",
            "R" to "r",
            "ZR" to "zr",
        ).forEach { (label, key) -> TestButton(label, key in pressed) }
    }
}

/** 十字键、两根摇杆与面键（面键是手柄上的那个菱形：上、左、右、下）。 */
@Composable
private fun ControllerDiagram(state: PadTestState?, compact: Boolean, face: FaceSlots) {
    if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Dpad(state?.pressed.orEmpty())
                FaceButtons(state?.pressed.orEmpty(), face)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StickIndicator("L", state?.leftX ?: 0f, state?.leftY ?: 0f)
                StickIndicator("R", state?.rightX ?: 0f, state?.rightY ?: 0f)
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dpad(state?.pressed.orEmpty())
            StickIndicator("L", state?.leftX ?: 0f, state?.leftY ?: 0f)
            StickIndicator("R", state?.rightX ?: 0f, state?.rightY ?: 0f)
            FaceButtons(state?.pressed.orEmpty(), face)
        }
    }
}

@Composable
private fun Dpad(pressed: Set<String>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TestButton("↑", "up" in pressed)
        Row {
            TestButton("←", "left" in pressed)
            Spacer(Modifier.width(42.dp))
            TestButton("→", "right" in pressed)
        }
        TestButton("↓", "down" in pressed)
    }
}

/**
 * 面键：手柄上的那个菱形（上面一颗，左边一颗，右边一颗，下面一颗）。
 *
 * 每格写的是**那个方位**的游戏键——玩家按位置还是按标签，它就跟着换；
 * 亮起来的是玩家此刻按着的那一颗。
 */
@Composable
private fun FaceButtons(pressed: Set<String>, slots: FaceSlots) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FaceButton(slots.top, pressed)
        Row {
            FaceButton(slots.left, pressed)
            Spacer(Modifier.width(42.dp))
            FaceButton(slots.right, pressed)
        }
        FaceButton(slots.bottom, pressed)
    }
}

/** 面键的一格：字是那个方位的游戏键，亮不亮是玩家按着没有。 */
@Composable
private fun FaceButton(target: String?, pressed: Set<String>) {
    TestButton(target?.uppercase() ?: "–", target != null && target in pressed)
}

/** 面键的四方：每个方位上的游戏键（没有绑定就是 null）。 */
private data class FaceSlots(
    val top: String?,
    val left: String?,
    val right: String?,
    val bottom: String?,
)

@Composable
private fun TestButton(label: String, pressed: Boolean) {
    val state = stringResource(
        if (pressed) R.string.pad_center_pressed else R.string.pad_center_released,
    )
    Box(
        Modifier.padding(2.dp).size(38.dp).clip(CircleShape)
            .background(if (pressed) NxPalette.Brand else NxPalette.Surface)
            .semantics { contentDescription = "$label, $state" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) NxPalette.Background else Color(0xFFA9A9B2),
            style = MaterialTheme.typography.titleMedium,
            fontSize = if (label.length > 2) 10.sp else 17.sp,
        )
    }
}

@Composable
private fun StickIndicator(label: String, x: Float, y: Float) {
    val description = stringResource(R.string.pad_center_stick_state, label, (x * 100).toInt(), (y * 100).toInt())
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            "$label  ${stringResource(R.string.pad_center_stick)}",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFA9A9B2),
        )
        Canvas(Modifier.padding(top = 8.dp).size(112.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension * 0.43f
            drawCircle(NxPalette.Surface, radius, center)
            drawCircle(NxPalette.Unbuilt, radius, center, style = Stroke(2.dp.toPx()))
            drawLine(NxPalette.Surface, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
            drawLine(NxPalette.Surface, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
            drawCircle(NxPalette.Brand, size.minDimension * 0.12f, Offset(center.x + x * radius, center.y + y * radius))
        }
    }
}

/** 一条校准的滑条。 */
@Composable
private fun CalibrationSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** 打开蓝牙设置（没接手柄时给玩家一条路）。 */
private fun openBluetoothSettings(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}
