package com.jianji.jizhang.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.HomeStyle
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.util.Calendar
import java.util.Locale

private fun money(cents: Long): String = String.format(Locale.CHINA, "%,.2f", cents / 100.0)

/**
 * 首页。只画内容 —— Scaffold 与底部导航由 [com.jianji.jizhang.ui.shell.AppShell] 提供。
 * 两套视觉都读同一份真实数据，只是排布不同。
 *
 * 数据在根 composable 里统一算好并用 remember 缓存：608 条账单只在依赖变化时才遍历一次，
 * 避免每帧重算（曾经每帧 filter 三遍，低端机会掉帧）。
 */
@Composable
fun HomeScreen(
    style: HomeStyle,
    all: List<TxWithCategory>,
    categories: List<CategoryEntity>,
    onToggleStyle: () -> Unit,
    onOpenTx: (TxWithCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 今天 0 点 / 本月 1 号 0 点，作为「今日」「本月」的判定边界。
    val todayStart = remember { todayStartMillis() }
    val monthStart = remember { monthStartMillis() }

    // 今日维度（旧版首页的缺口）：只筛今天，支出/收入分开。
    val todayTx = remember(all, todayStart) { all.filter { it.tx.dateTime >= todayStart } }
    val todayExpense = remember(todayTx) { todayTx.filter { it.tx.isExpense }.sumOf { it.tx.amountCents } }
    val todayIncome = remember(todayTx) { todayTx.filter { it.tx.isExpense.not() }.sumOf { it.tx.amountCents } }

    // 本月维度：支出 / 收入 / 结余。
    val monthTx = remember(all, monthStart) { all.filter { it.tx.dateTime >= monthStart } }
    val monthExpense = remember(monthTx) { monthTx.filter { it.tx.isExpense }.sumOf { it.tx.amountCents } }
    val monthIncome = remember(monthTx) { monthTx.filter { it.tx.isExpense.not() }.sumOf { it.tx.amountCents } }
    val balanceCents = monthIncome - monthExpense

    // 本月「花在哪了」：按分类聚合支出（未分类归为 null 一组），降序取前 3，
    // 用于一条横向占比条，比单纯列清单更有层次。
    val monthTop = remember(monthTx) {
        monthTx.filter { it.tx.isExpense }
            .groupBy { it.category }
            .map { (cat, list) -> cat to list.sumOf { it.tx.amountCents } }
            .sortedByDescending { it.second }
            .take(3)
    }

    val recent = remember(all) { all.take(8) }
    val empty = all.isEmpty()

    when (style) {
        HomeStyle.WHITE -> WhiteHome(
            onToggleStyle = onToggleStyle,
            onOpenTx = onOpenTx,
            modifier = modifier,
            balance = balanceCents,
            monthExpense = monthExpense,
            monthIncome = monthIncome,
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            monthTop = monthTop,
            recent = recent,
            totalCount = all.size,
            empty = empty,
        )
        HomeStyle.BLUE -> BlueHome(
            onToggleStyle = onToggleStyle,
            onOpenTx = onOpenTx,
            modifier = modifier,
            balance = balanceCents,
            monthExpense = monthExpense,
            monthIncome = monthIncome,
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            monthTop = monthTop,
            recent = recent,
            totalCount = all.size,
            empty = empty,
        )
    }
}

private fun monthStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun todayStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/* ---------------- 方案 A · 白底清朗 ---------------- */

@Composable
private fun WhiteHome(
    onToggleStyle: () -> Unit,
    onOpenTx: (TxWithCategory) -> Unit,
    modifier: Modifier = Modifier,
    balance: Long,
    monthExpense: Long,
    monthIncome: Long,
    todayExpense: Long,
    todayIncome: Long,
    monthTop: List<Pair<CategoryEntity?, Long>>,
    recent: List<TxWithCategory>,
    totalCount: Int,
    empty: Boolean,
) {
    // 页面底（浅冷灰）之上浮起纯白卡片 —— surface 与 background 拉开才有层次。
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        MonthHeader(
            monthColor = MaterialTheme.colorScheme.onBackground,
            onToggleStyle = onToggleStyle,
            toggleTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        // 主卡：本月结余大数字 + 支出/收入两个子块。
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text(
                    "本月结余",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "¥ " + money(balance),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(14.dp))
                // 两个子块平分一行：支出用语义红、收入用语义绿，数字本身即信息。
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "本月支出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "¥ " + money(monthExpense),
                            style = MaterialTheme.typography.titleMedium,
                            color = JizhangTheme.colors.expense,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "本月收入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "¥ " + money(monthIncome),
                            style = MaterialTheme.typography.titleMedium,
                            color = JizhangTheme.colors.income,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 今日卡：一行两块，底色用语义浅底，圆角 medium。都为 0 也照常显示。
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TodayBlock(
                modifier = Modifier.weight(1f),
                label = "今日支出",
                amount = money(todayExpense),
                container = JizhangTheme.colors.expenseContainer,
                valueColor = JizhangTheme.colors.expense,
            )
            TodayBlock(
                modifier = Modifier.weight(1f),
                label = "今日收入",
                amount = money(todayIncome),
                container = JizhangTheme.colors.incomeContainer,
                valueColor = JizhangTheme.colors.income,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 「本月花在哪」：仅当 Top3 非空。一条占比条远胜纯文字清单。
        if (monthTop.isNotEmpty()) {
            CategoryProportionCard(monthTop = monthTop)
            Spacer(Modifier.height(12.dp))
        }

        // 最近账单：做成白卡，行之间用淡分隔线，像一张列表卡片。
        RecentCard(recent = recent, totalCount = totalCount, empty = empty, onOpenTx = onOpenTx)
        Spacer(Modifier.height(16.dp))
    }
}

/* ---------------- 方案 B · 品牌蓝顶 ---------------- */

@Composable
private fun BlueHome(
    onToggleStyle: () -> Unit,
    onOpenTx: (TxWithCategory) -> Unit,
    modifier: Modifier = Modifier,
    balance: Long,
    monthExpense: Long,
    monthIncome: Long,
    todayExpense: Long,
    todayIncome: Long,
    monthTop: List<Pair<CategoryEntity?, Long>>,
    recent: List<TxWithCategory>,
    totalCount: Int,
    empty: Boolean,
) {
    // 整页白底，蓝色块只压在顶部；内容区圆角向上叠压蓝顶，形成「卡片下钻」层次。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // 蓝顶：顶到状态栏下，白字。底部多留 8dp 给叠压的弧度。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 28.dp),
        ) {
            MonthHeader(
                monthColor = Color.White,
                onToggleStyle = onToggleStyle,
                toggleTint = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "本月结余",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
            Text(
                "¥ " + money(balance),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            // 半透明白底块（Color(0x1FFFFFFF) 为规范要求的「白字浅底」透明白），文字白。
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BlueMetricBlock(
                    modifier = Modifier.weight(1f),
                    label = "本月支出",
                    amount = money(monthExpense),
                    container = Color(0x1FFFFFFF),
                    valueColor = Color.White,
                )
                BlueMetricBlock(
                    modifier = Modifier.weight(1f),
                    label = "本月收入",
                    amount = money(monthIncome),
                    container = Color(0x1FFFFFFF),
                    valueColor = Color.White,
                )
            }
        }

        // 内容区：白底 + 顶部大圆角，向上叠压蓝顶 20dp。不加 statusBarsPadding（蓝顶已加）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-20).dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TodayBlock(
                    modifier = Modifier.weight(1f),
                    label = "今日支出",
                    amount = money(todayExpense),
                    container = JizhangTheme.colors.expenseContainer,
                    valueColor = JizhangTheme.colors.expense,
                )
                TodayBlock(
                    modifier = Modifier.weight(1f),
                    label = "今日收入",
                    amount = money(todayIncome),
                    container = JizhangTheme.colors.incomeContainer,
                    valueColor = JizhangTheme.colors.income,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (monthTop.isNotEmpty()) {
                CategoryProportionCard(monthTop = monthTop)
                Spacer(Modifier.height(12.dp))
            }
            RecentCard(recent = recent, totalCount = totalCount, empty = empty, onOpenTx = onOpenTx)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/* ---------------- 复用组件 ---------------- */

@Composable
private fun MonthHeader(monthColor: Color, onToggleStyle: () -> Unit, toggleTint: Color) {
    // 月份/年份按当天动态取，避免写成「九月 2026」后跨月也不变。
    // Calendar 在 remember 里取一次即可（首页不会跨月常驻）。
    val now = remember { Calendar.getInstance() }
    val monthName = remember {
        now.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.CHINA)
            ?: "${now.get(Calendar.MONTH) + 1}月"
    }
    val year = remember { now.get(Calendar.YEAR) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(monthName, style = MaterialTheme.typography.titleLarge, color = monthColor)
        Text(
            "  $year",
            style = MaterialTheme.typography.bodySmall,
            color = monthColor.copy(alpha = 0.55f),
        )
        Spacer(Modifier.weight(1f))
        Icon(
            JizhangIcons.Palette,
            contentDescription = "切换首页风格",
            tint = toggleTint,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleStyle),
        )
    }
}

@Composable
private fun TodayBlock(
    modifier: Modifier = Modifier,
    label: String,
    amount: String,
    container: Color,
    valueColor: Color,
) {
    // 今日卡的一块：语义浅底 + 圆角 medium，数字用 titleMedium。
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = container) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("¥ $amount", style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
    }
}

@Composable
private fun BlueMetricBlock(
    modifier: Modifier = Modifier,
    label: String,
    amount: String,
    container: Color,
    valueColor: Color,
) {
    // 蓝顶里的半透明白底指标块，文字浅色。
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = container) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(4.dp))
            Text("¥ $amount", style = MaterialTheme.typography.titleMedium, color = valueColor)
        }
    }
}

/**
 * 占比条的一段。颜色先在外层 composable 作用域里算好（含未分类回退 outline），
 * 再传给无状态渲染 —— 绝不把 MaterialTheme 读进非 composable lambda。
 */
private data class ProportionSegment(
    val color: Color,
    val weight: Float,
    val name: String,
    val amount: String,
)

@Composable
private fun CategoryProportionCard(monthTop: List<Pair<CategoryEntity?, Long>>) {
    val outline = MaterialTheme.colorScheme.outline
    // 三段权重按其在 Top3 中的占比，合计填满整条；无分类用 outline 占位。
    val segments = remember(monthTop) {
        val total = monthTop.sumOf { it.second }.coerceAtLeast(1L)
        monthTop.map { (cat, amt) ->
            ProportionSegment(
                color = cat?.let { Color(it.color) } ?: outline,
                weight = amt.toFloat() / total,
                name = cat?.name ?: "未分类",
                amount = money(amt),
            )
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "本月花在哪",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            // 横向堆叠占比条：Row + 三段 weight 的 Box，裁切成小圆角。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.small),
            ) {
                segments.forEach { seg ->
                    Box(Modifier.weight(seg.weight).fillMaxHeight().background(seg.color))
                }
            }
            Spacer(Modifier.height(10.dp))
            // 图例：色点 + 分类名 + 金额，一行三列。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                segments.forEach { seg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(seg.color))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            seg.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "¥ " + seg.amount,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    recent: List<TxWithCategory>,
    totalCount: Int,
    empty: Boolean,
    onOpenTx: (TxWithCategory) -> Unit,
) {
    // 分隔线颜色先提到作用域，避免进 forEach 的 composable lambda 里重复读；淡一点更精致。
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "最近账单",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "共 $totalCount 笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (empty) {
                EmptyHint()
            } else {
                recent.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = dividerColor)
                    TxRow(item, onOpenTx)
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    // 空态：居中图标 + 提示，引导用户去点底部 +。
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            JizhangIcons.Empty,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "还没有账单，点下面的 + 记一笔",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TxRow(item: TxWithCategory, onOpenTx: (TxWithCategory) -> Unit) {
    val tx = item.tx
    val cat = item.category
    // 金额色走语义色；圆点底色改用语义浅底（替换旧的写死 #FCEBEB / #EAF3EE）。
    val tint = if (tx.isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income
    val container = if (tx.isExpense) JizhangTheme.colors.expenseContainer else JizhangTheme.colors.incomeContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTx(item) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分类自身颜色是数据色，允许直接用；未分类回退到语义浅底。
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(cat?.let { Color(it.color) } ?: container),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (cat?.name ?: "其").take(1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cat?.name ?: "未分类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (tx.note.isNotBlank()) {
                Text(
                    tx.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            (if (tx.isExpense) "-" else "+") + money(tx.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}
