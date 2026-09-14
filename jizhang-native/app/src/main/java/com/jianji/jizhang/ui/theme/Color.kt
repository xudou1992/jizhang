package com.jianji.jizhang.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 色彩令牌。
 *
 * 命名纪律：**绑语义，不绑颜色名**。
 * 上一版把「紫」这个变量刷成品牌蓝、于是 `Purple` 装着蓝色 —— 这种谎言不要再犯。
 *
 * 这一版相比初版的调整（都是实机观感问题）：
 * 1. 支出红 `#D93A3A` 偏暗发闷，改 `#E5484D`（Radix red 9），更亮更清晰但不刺眼。
 * 2. 收入绿 `#1F9254` 太灰，改 `#128A5B`，在白底上对比度更高。
 * 3. 中性色改用带一点冷调的灰阶（Radix slate 系），白底不再是纯白，
 *    卡片与页面之间才有层次 —— 之前 `#FCFCFF` 配纯白卡片等于没有层次。
 * 4. 补齐 10 个分类色板。分类色是**数据**不是设计色，但它决定饼图/环形图好不好看，
 *    所以在这里集中定义，避免各处随手写死。
 */

/* ---------------- 品牌：清朗蓝 ---------------- */

val Brand = Color(0xFF2A5AE8)
val BrandLight = Color(0xFF7A9BFF)
val BrandDark = Color(0xFF1B3FB0)

/** 品牌浅底（选中态、标签底色）。比初版更淡，避免大面积铺色时压字。 */
val BrandContainer = Color(0xFFE3EAFE)
val BrandContainerDark = Color(0xFF1A2140)
val BrandOnContainer = Color(0xFF10245C)

/* ---------------- 语义：支出红 / 收入绿 ---------------- */
// 注意：这与股票「红涨绿跌」是两套语境，不要「统一」。

val Expense = Color(0xFFE5484D)
val Income = Color(0xFF128A5B)
val Transfer = Color(0xFF6E7684)

/** 语义色的浅底容器（列表行、汇总块）。 */
val ExpenseContainer = Color(0xFFFDECEC)
val IncomeContainer = Color(0xFFE7F6EF)
val TransferContainer = Color(0xFFF0F1F4)

val ExpenseContainerDark = Color(0xFF3B1D1F)
val IncomeContainerDark = Color(0xFF12301F)
val TransferContainerDark = Color(0xFF262A31)

/* ---------------- 中性（冷调灰阶） ---------------- */

/** 页面底：极浅冷灰，让纯白卡片浮起来。 */
val Surface = Color(0xFFF7F8FB)

/** 卡片面：纯白。 */
val CardSurface = Color(0xFFFFFFFF)

/** 次级填充面：输入框、进度槽、分组底。 */
val SurfaceVariant = Color(0xFFEFF1F6)
val SurfaceVariantStrong = Color(0xFFE4E7EE)

val Outline = Color(0xFFDDE1E9)
val OutlineStrong = Color(0xFFBFC5D2)

val TextPrimary = Color(0xFF14161C)
val TextSecondary = Color(0xFF616773)
val TextTertiary = Color(0xFF8B93A1)

/* ---------------- 中性 · 深色 ---------------- */

val SurfaceDark = Color(0xFF101216)
val CardSurfaceDark = Color(0xFF1A1D24)
val SurfaceVariantDark = Color(0xFF23262E)
val OutlineDark = Color(0xFF333842)
val TextPrimaryDark = Color(0xFFF2F4F8)
val TextSecondaryDark = Color(0xFFA8AFBC)

/** 真·黑（AMOLED）：卡片与页面都黑，靠描边区分层次。 */
val PureBlack = Color(0xFF000000)

/* ---------------- 分类色板（数据色，供新建分类时挑选） ---------------- */

val CategoryPalette: List<Color> = listOf(
    Color(0xFFE5484D), // 红
    Color(0xFFE8730C), // 橙
    Color(0xFFE2B008), // 黄
    Color(0xFF128A5B), // 绿
    Color(0xFF0F9B9B), // 青
    Color(0xFF2A5AE8), // 蓝
    Color(0xFF6E56CF), // 紫
    Color(0xFFC4348F), // 品红
    Color(0xFF8A6A4E), // 棕
    Color(0xFF6E7684), // 灰
)
