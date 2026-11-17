package com.nx.android.feature.faq

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.components.CloseIcon
import com.nx.android.core.ui.components.Hint
import com.nx.android.core.ui.components.rememberPageScroll
import com.nx.android.core.ui.nav.NavKey
import com.nx.android.core.ui.nav.OnNavKey
import com.nx.android.core.ui.theme.GlassPanel
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.feature.updates.openRepo

/**
 * 常见问题页。
 *
 * 它是一页，不是一个浮在上面的东西：整块盖住顶栏与下面的页面（跟设置页一样）。
 * 上面那张 GitHub 卡片是仓库与反馈的出口，底下是分组的问题，点问题展开答案。
 *
 * 内容是文件里的数据（`assets/faq/`），按 App 的界面语言挑一份：语言换了要重读。
 * 答案只说「去哪儿找」，不复述别的页面的操作步骤（那些页面自己会说），
 * 也不写任何游戏或密钥的来源——那是玩家自己的机器上的事。
 */
@Composable
fun FaqScreen(onClose: () -> Unit, navActive: Boolean = false) {
    val scroll = rememberPageScroll()
    BackHandler { onClose() }
    // 手柄的返回键：跟系统的返回键一条路（这一页只做「能关掉」）
    OnNavKey(enabled = navActive) { key ->
        if (key == NavKey.Back) onClose() }
    // 展开的是哪一条（组 key + 序号）：一次只开一条
    var open by remember { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current
    val locale = LocalAppLocale.current
    val cfg = LocalConfiguration.current
    // 内容是文件里的数据：界面语言换了要重读（跟着系统时，手机语言变了也算）
    val groups = remember(locale.mode, cfg) {
        FaqContent.load(ctx, FaqContent.fileFor(locale.mode, cfg.locales[0].language))
    }

    // 它是一页，不是一个浮在上面的东西：整块盖住，连边框圆角都不留
    Box(Modifier.fillMaxSize().background(NxPalette.Surface)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 页头：这一页自己的名字与出去的入口
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.faq_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                CloseIcon(onClose)
            }
            Column(
                Modifier.weight(1f).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GitHubCard()
                if (groups.isEmpty()) {
                    // 内容没读出来：这一页照样开着（上面那张卡片还能用）
                    Hint(stringResource(R.string.faq_empty))
                } else {
                    for (group in groups) {
                        FaqGroupSection(group, open) { open = if (open == it) null else it }
                    }
                }
            }
        }
    }
}

/**
 * 顶部那张卡片：仓库与反馈的出口。
 *
 * 整块点得开：设备上没有能处理网页地址的应用时什么也不做（openRepo 自己管这件事），
 * 不报错，也不挡这一页。
 */
@Composable
private fun GitHubCard() {
    val ctx = LocalContext.current
    GlassPanel(modifier = Modifier.fillMaxWidth().clickable { openRepo(ctx) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(painterResource(R.drawable.ic_launcher_mark), null, Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.faq_github_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Hint(stringResource(R.string.faq_github_hint))
            }
            Text(
                stringResource(R.string.faq_github_action),
                color = NxPalette.Brand,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                null,
                Modifier.size(16.dp),
                tint = NxPalette.Brand,
            )
        }
    }
}

/** 一组问答：组标题与这一组的问题（问题排在组标题下面） */
@Composable
private fun FaqGroupSection(group: FaqGroup, open: String?, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            group.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        GlassPanel(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
            group.entries.forEachIndexed { i, entry ->
                if (i > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                val id = "${group.key}/$i"
                FaqRow(entry, open == id) { onToggle(id) }
            }
        }
    }
}

/** 一条问答：问题是标题，答案是展开之后出现的那段字 */
@Composable
private fun FaqRow(entry: FaqEntry, expanded: Boolean, onToggle: () -> Unit) {
    // 箭头跟着展开的方向转
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "faq-arrow")
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                null,
                Modifier.size(20.dp).rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Text(
                entry.answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        }
    }
}
