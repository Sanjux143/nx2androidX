package com.nx.android.game.hud

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nx.android.data.AppVersion
import com.nx.android.data.drivers.Drivers
import com.nx.android.data.playback.PackEnvStore
import com.nx.android.game.hud.HostSample
import com.nx.android.game.hud.HudData
import com.nx.android.game.hud.HudField
import com.nx.android.game.hud.HudRole
import com.nx.android.game.hud.HudSample
import com.nx.android.game.hud.HudSampler
import com.nx.android.game.Native
import com.nx.android.core.ui.theme.NxPalette
import kotlinx.coroutines.delay
import java.io.File

/**
 * The performance HUD: a line of readings over the game, at the top centre, in a dark pill. It samples once a
 * second — the host's counters for the frame rate, the device's own readings for the rest — and reads only the
 * fields the player selected. The device line below it is static: who this machine is, for a screenshot in a
 * bug report.
 *
 * It takes no touches: the touches belong to the game (PadView works out what they hit).
 */
@Composable
fun HudOverlay(
    enabled: Boolean,
    deviceLine: Boolean,
    fields: Set<HudField>,
    fahrenheit: Boolean,
    engineLabel: () -> String?,
    chargingText: String,
    packPath: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (enabled) PerfHud(fields, fahrenheit, engineLabel, chargingText)
        if (deviceLine) DeviceLine(packPath)
    }
}

@Composable
private fun PerfHud(
    fields: Set<HudField>,
    fahrenheit: Boolean,
    engineLabel: () -> String?,
    chargingText: String,
) {
    val ctx = LocalContext.current
    var sample by remember { mutableStateOf<HudSample?>(null) }
    // the host's counters: the base of the frame rate's difference, kept across a change of the fields
    var prevHost by remember { mutableStateOf<HostSample?>(null) }
    var prevAt by remember { mutableLongStateOf(0L) }

    // the fields are the key: a change re-reads the device at once, so a newly picked field shows up right away
    LaunchedEffect(fields) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val raw = runCatching { Native.perfSample() }.getOrNull()
            if (raw != null && raw.size >= 5) {
                val cur = HostSample(raw[0], raw[1], raw[2], raw[3], raw[4])
                val stats = HudData.stats(prevHost, cur, now - prevAt)
                prevHost = cur
                prevAt = now
                sample = HudSampler.take(ctx, fields).copy(
                    gameFps = stats.gameFps,
                    presentedFps = stats.presentedFps,
                    frameMs = stats.frameMs,
                    // the frame generation's state (the label follows it): an old host has no entry for it
                    fgState = HudData.fgState(runCatching { Native.frameGenStatus() }.getOrNull()),
                )
            } else {
                sample = HudSampler.take(ctx, fields)   // the host is not up yet: the device's readings alone
            }
            delay(1000)
        }
    }

    val lines = sample?.let { HudData.render(it, fields, fahrenheit, engineLabel(), chargingText) } ?: emptyList()
    if (lines.isEmpty()) return
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lines.forEach {
            Text(it.text, color = roleColor(it.role), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * The device line: who this machine is, for a screenshot in a bug report. Static: it is read once, when the
 * line is asked for, and does not change while the game runs.
 */
@Composable
private fun DeviceLine(packPath: String?) {
    val ctx = LocalContext.current
    val text = remember(packPath) { deviceLine(ctx, packPath) }
    if (text.isEmpty()) return
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color(0xFFEDEDF0), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** The line's parts: the app's version, the Vulkan driver in use, the machine, the chip, the system */
private fun deviceLine(ctx: Context, packPath: String?): String {
    val parts = mutableListOf<String>()
    val version = AppVersion.of(ctx)
    if (version.isNotEmpty()) parts += "nx2androidX $version"
    driverLabel(ctx, packPath)?.let { parts += it }
    if (Build.MODEL.isNotEmpty()) parts += Build.MODEL
    if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotEmpty()) parts += Build.SOC_MODEL
    parts += "Android ${Build.VERSION.RELEASE}"
    return parts.joinToString(" · ")
}

/** The Vulkan driver this game runs with: the installed one it names, or the one the pack carries */
private fun driverLabel(ctx: Context, packPath: String?): String? {
    if (packPath.isNullOrEmpty()) return null
    val drv = runCatching { PackEnvStore.current(File(packPath)).second }.getOrNull()
    if (drv.isNullOrBlank()) return null   // the device's own driver
    val installed = runCatching { Drivers.list(ctx) }.getOrDefault(emptyList())
    installed.firstOrNull { it.library.absolutePath == drv }?.let { return it.label }
    return File(drv).name
}

/** The pill's palette: the app's own colors */
private fun roleColor(role: HudRole): Color = when (role) {
    HudRole.PLAIN -> Color(0xFFEDEDF0)
    HudRole.ACCENT -> NxPalette.Brand
    HudRole.GOOD -> NxPalette.Ready
    HudRole.WARN -> NxPalette.Building
    HudRole.BAD -> NxPalette.Danger
}
