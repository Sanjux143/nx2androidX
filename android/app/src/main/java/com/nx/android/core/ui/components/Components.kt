package com.nx.android.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.theme.GlassPanel
import com.nx.android.core.ui.theme.NxPalette

/** A titled section: the screen is a few glass cards, each with its heading */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GlassPanel(modifier = modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** The main action: a cream capsule, like the launcher's */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = NxPalette.Surface,
            disabledContentColor = NxPalette.Unbuilt,
        ),
        modifier = modifier.height(46.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/**
 * The secondary action: a quiet capsule.
 *
 * `trailing` draws a mark at the button's right edge (a dropdown's arrow): the label then takes the rest of the
 * button's width, so the mark sits in the same column in every field, whatever the label's length.
 */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        modifier = modifier.height(44.dp),
    ) {
        if (trailing == null) {
            Text(text, fontSize = 14.sp)
        } else {
            Text(text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

/** A status dot: one color per state, the same everywhere (library cards, builds rows, settings) */
@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** A small line of muted text */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, size: TextUnit = 12.sp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = size,
        modifier = modifier,
    )
}

/** A page's way out: a cross in a round button */
@Composable
fun CloseIcon(onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            stringResource(R.string.common_close),
            Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The pages' scroll states, saveable across the tab switches (a hidden page's composition goes away; the scroll position must not) */
@Composable
fun rememberPageScroll(): ScrollState =
    rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

/**
 * An option row: the name on the left, a button on the right that opens the options. Each option is a pair:
 * the value (what gets written) and the name (what the player reads) — they differ for the drivers installed in
 * the App folder (the value is its path), so the button shows the name.
 *
 * `fallback` is what the button shows when the current value is not among the options (a driver was deleted, the
 * pack's file is gone).
 *
 * `hint` is a line the player asks for: a question mark beside the row shows it, and hides it again.
 */
@Composable
fun PickRow(label: String, options: List<Pair<String, String>>, current: String?, onSelect: (String) -> Unit,
            fallback: String, hint: String? = null) {
    var open by remember { mutableStateOf(false) }
    var hintOpen by remember { mutableStateOf(false) }
    // 现在的值不在选项里（驱动被删了、包里的文件没了）：显示它最后的名字，别把整条路径摊出来
    val shown = options.firstOrNull { it.first == current }?.second
        ?: current?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: fallback
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(90.dp))
            Box {
                // 按钮定宽：箭头钉在右端，两个字段的箭头排成一列（值的长度不再影响它的位置）
                QuietButton(shown, onClick = { open = !open },
                    modifier = Modifier.width(200.dp),
                    trailing = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp)) })
                androidx.compose.material3.DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.width(200.dp),
                ) {
                    for ((value, name) in options.distinctBy { it.first }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(name, fontWeight = if (value == current) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { open = false; onSelect(value) },
                        )
                    }
                }
            }
            if (hint != null) {
                androidx.compose.material3.IconButton(onClick = { hintOpen = !hintOpen },
                    modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.HelpOutline, stringResource(R.string.common_hint),
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (hint != null && hintOpen) Hint(hint, size = 11.sp)
    }
}

