package com.nx.android.core.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The palette: the launcher's colors from the desktop (NX2Apple), the same six the ported games' launcher pages
 * wear — near-black ground, deep panels, cream for the main action, one color per status — plus the brand's
 * orange, the one the app's own icon carries.
 */
object NxPalette {
    val Background = Color(0xFF0E0E0F)
    val Surface = Color(0xFF1A1A1C)
    val Cream = Color(0xFFFFF0D6)
    val Brand = Color(0xFFFF8A00)
    val Ready = Color(0xFF40D678)
    val Building = Color(0xFFFFC73D)
    val Unbuilt = Color(0xFF91949B)
    val Danger = Color(0xFFFF6B66)
}

private val NxColors = darkColorScheme(
    primary = NxPalette.Cream,
    onPrimary = Color(0xFF141416),
    secondary = NxPalette.Cream.copy(alpha = 0.16f),
    onSecondary = NxPalette.Cream,
    background = NxPalette.Background,
    onBackground = Color(0xFFEDEDF0),
    surface = NxPalette.Surface,
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = NxPalette.Surface,
    onSurfaceVariant = Color(0xFFA9A9B2),
    error = NxPalette.Danger,
    onError = Color(0xFF141416),
    outlineVariant = Color(0x15FFFFFF),
)

/** 主机式排版层级：大标题要敢用大字，正文紧凑（数值照 capyps5 的层级，颜色仍是我们的） */
private val NxTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

/** The app is dark, always: the games are dark, the launcher matches them (the system's scheme is not followed) */
@Composable
fun NxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NxColors, typography = NxTypography) {
        // 页面自己画背景（不用 Surface），默认的文字颜色在这里给足：没写 color 的 Text 才不会黑字落黑底
        // 水波纹全关：Material3 的主题波纹读 LocalRippleConfiguration，给 null 就不再画。
        // 组件自己的 ripple() 与裸 Modifier.clickable 都走这一条路（MaterialTheme 把 LocalIndication
        // 指向了那个节点），所以这一行关掉的是全 app 的波纹。
        // 为什么不要：抽屉画在跑着的游戏之上，水波纹那层按住的每一帧都要重绘整个界面层，
        // 跟游戏抢 GPU（120Hz 的屏上就是每秒 120 次）。而这层反馈本来就看不见——选中、开关
        // 都是元素自己的颜色在说话。
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides NxColors.onBackground,
            androidx.compose.material3.LocalRippleConfiguration provides null,
            content = content
        )
    }
}

/**
 * A panel of frosted glass: a deep translucent ground with a hairline, and from Android 12 the ground itself is
 * blurred once more behind the content (below that it is just the translucent ground — the look holds, the
 * blur is the flourish).
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
        Box(
            Modifier
                .matchParentSize()
                .blur(if (Build.VERSION.SDK_INT >= 31) 16.dp else 0.dp, BlurredEdgeTreatment.Unbounded)
                .background(NxPalette.Surface.copy(alpha = 0.72f))
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent))
            )
        )
        androidx.compose.foundation.layout.Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * A glass sheet that fills its box (the overlays' ground)
 *
 * [alpha] is how much of the sheet's own ground shows through: the overlays that cover
 * one small thing keep the default (the page behind stays visible), a page that covers
 * everything asks for nearly opaque — otherwise what is behind it bleeds through and
 * the two pages' words collide.
 */
@Composable
fun GlassSheet(
    modifier: Modifier = Modifier,
    alpha: Float = 0.78f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
    ) {
        Box(
            Modifier
                .matchParentSize()
                .blur(if (Build.VERSION.SDK_INT >= 31) 20.dp else 0.dp, BlurredEdgeTreatment.Unbounded)
                .background(NxPalette.Surface.copy(alpha = alpha))
        )
        Box(Modifier.matchParentSize(), content = content)
    }
}
