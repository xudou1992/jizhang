package com.jianji.jizhang.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语义色槽位。页面取色一律走 `JizhangTheme.colors.expense`，
 * 不要直接引用 `Color(0x...)`，也不要按颜色名命名令牌。
 */
data class SemanticColors(
    val expense: Color,
    val income: Color,
    val transfer: Color,
    /** 语义色配套的浅底容器，列表行/汇总块用它，避免大面积铺纯色。 */
    val expenseContainer: Color,
    val incomeContainer: Color,
    val transferContainer: Color,
    /** 卡片面。深色模式下比页面底稍亮，靠明度分层次。 */
    val card: Color,
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        expense = Expense,
        income = Income,
        transfer = Transfer,
        expenseContainer = ExpenseContainer,
        incomeContainer = IncomeContainer,
        transferContainer = TransferContainer,
        card = CardSurface,
    )
}

/** 是否处于深色（含 AMOLED）。页面需要单独处理「黑底白字」时读它。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 中文正文比 Material3 默认要「松」：行高按字号 ×1.62。
 * 加粗用 Medium(500) 而不是 Bold(700) —— 黑体在 700 会糊。
 */
private val JizhangTypography: Typography = Typography().let { base ->
    base.copy(
        // 金额/标题类：等宽数字，否则右对齐的金额会参差不齐
        displaySmall = TextStyle(
            fontSize = 34.sp,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        headlineMedium = TextStyle(
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        headlineSmall = TextStyle(
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        // 正文放松行高
        bodyLarge = base.bodyLarge.copy(lineHeight = base.bodyLarge.fontSize * 1.62),
        bodyMedium = base.bodyMedium.copy(lineHeight = base.bodyMedium.fontSize * 1.62),
        bodySmall = base.bodySmall.copy(lineHeight = base.bodySmall.fontSize * 1.62),
    )
}

/**
 * 圆角。加大一档：小圆角是「旧安卓」观感的主要来源之一。
 * 卡片 20dp、按钮 16dp、列表行 16dp、芯片 999（胶囊）。
 */
val JizhangShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandContainer,
    onPrimaryContainer = BrandOnContainer,
    secondary = Transfer,
    onSecondary = Color.White,
    // 页面底 = 浅冷灰；卡片面 = 纯白。两者拉开才有层次。
    background = Surface,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    outlineVariant = Outline.copy(alpha = 0.6f),
    error = Expense,
    onError = Color.White,
    errorContainer = ExpenseContainer,
    onErrorContainer = Color(0xFF5C1417),
    // 关键：不要用 surfaceTint 染色。浅色方案下 tint 会让 elevation 越高卡片越脏。
    surfaceTint = Color.Transparent,
)

private val DarkScheme = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF0A1B4D),
    primaryContainer = BrandContainerDark,
    onPrimaryContainer = Color(0xFFC9D8FF),
    secondary = TextSecondaryDark,
    onSecondary = Color(0xFF14161C),
    background = SurfaceDark,
    onBackground = TextPrimaryDark,
    surface = CardSurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark.copy(alpha = 0.6f),
    error = Color(0xFFFF6B6E),
    onError = Color(0xFF3B1012),
    errorContainer = ExpenseContainerDark,
    onErrorContainer = Color(0xFFFFD6D7),
    surfaceTint = Color.Transparent,
)

private val LightSemantic = SemanticColors(
    expense = Expense,
    income = Income,
    transfer = Transfer,
    expenseContainer = ExpenseContainer,
    incomeContainer = IncomeContainer,
    transferContainer = TransferContainer,
    card = CardSurface,
)

private val DarkSemantic = SemanticColors(
    expense = Color(0xFFFF6B6E),
    income = Color(0xFF3DD68C),
    transfer = Color(0xFF9AA3B2),
    expenseContainer = ExpenseContainerDark,
    incomeContainer = IncomeContainerDark,
    transferContainer = TransferContainerDark,
    card = CardSurfaceDark,
)

/**
 * @param darkTheme 是否深色
 * @param trueBlack AMOLED 真黑：页面与卡片都压到 #000，靠描边分层次。
 *                  用户设置里开了「纯黑」才传 true。
 */
@Composable
fun JizhangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        !darkTheme -> LightScheme
        trueBlack -> DarkScheme.copy(
            background = PureBlack,
            surface = PureBlack,
            surfaceVariant = Color(0xFF14161A),
            outline = Color(0xFF2A2E36),
        )
        else -> DarkScheme
    }
    val semantic = if (darkTheme) {
        DarkSemantic.copy(card = if (trueBlack) PureBlack else CardSurfaceDark)
    } else {
        LightSemantic
    }

    CompositionLocalProvider(
        LocalSemanticColors provides semantic,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = JizhangTypography,
            shapes = JizhangShapes,
            content = content,
        )
    }
}

object JizhangTheme {
    val colors: SemanticColors
        @Composable get() = LocalSemanticColors.current

    /** 当前是否深色（含 AMOLED）。 */
    val isDark: Boolean
        @Composable get() = LocalDarkTheme.current
}
