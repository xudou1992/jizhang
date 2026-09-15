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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.BudgetEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.isTransfer
import com.jianji.jizhang.data.monthEndMillis
import com.jianji.jizhang.data.nonTransfers
import com.jianji.jizhang.data.monthStartMillis
import com.jianji.jizhang.data.todayEndMillis
import com.jianji.jizhang.data.todayStartMillis
import com.jianji.jizhang.ui.budget.BudgetEditorDialog
import com.jianji.jizhang.ui.budget.CategoryBudgetRow
import com.jianji.jizhang.ui.budget.TotalBudgetSummary
import com.jianji.jizhang.ui.budget.categoryBudgetRows
import com.jianji.jizhang.ui.budget.monthKey
import com.jianji.jizhang.ui.budget.summarizeTotalBudget
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.HomeStyle
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.readableOn
import java.util.Calendar
import java.util.Locale

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
    // 预算三参全带默认值：MainActivity 的既有调用点一行不改也编得过，
    // 主控接 VM 时只需把 budgets/onSetBudget/onRemoveBudget 传进来。
    budgets: List<BudgetEntity> = emptyList(),
    onSetBudget: (id: String, targetId: String, amountCents: Long, carryOver: Boolean, startMonth: String) -> Unit = { _, _, _, _, _ -> },
    onRemoveBudget: (String) -> Unit = {},
) {
    // 今天 0 点 / 本月 1 号 0 点，作为「今日」「本月」的判定边界。
    // 成对取上下界：只有下界的话，未来日期的账会被算进「今日 / 本月」。
    val todayRange = remember { todayStartMillis()..todayEndMillis() }
    val monthRange = remember { monthStartMillis()..monthEndMillis() }

    // 预算相关的「now」取一次即可：卡片不需要跨零点自动刷新（与 todayRange 同一已知取舍）。
    val nowMillis = remember { System.currentTimeMillis() }
    val thisMonth = remember(nowMillis) { monthKey(nowMillis) }

    // 今日维度（旧版首页的缺口）：只筛今天，支出/收入分开。
    // nonTransfers()：转账行恒 isExpense=true，不排除会把「信用卡还花呗」计成消费。
    val todayTx = remember(all, todayRange) { all.filter { it.tx.dateTime in todayRange } }
    val todayExpense = remember(todayTx) { todayTx.nonTransfers().filter { it.tx.isExpense }.sumOf { it.tx.amountCents } }
    val todayIncome = remember(todayTx) { todayTx.nonTransfers().filter { it.tx.isExpense.not() }.sumOf { it.tx.amountCents } }

    // 本月维度：支出 / 收入 / 结余。
    val monthTx = remember(all, monthRange) { all.filter { it.tx.dateTime in monthRange } }
    val monthExpense = remember(monthTx) { monthTx.nonTransfers().filter { it.tx.isExpense }.sumOf { it.tx.amountCents } }
    val monthIncome = remember(monthTx) { monthTx.nonTransfers().filter { it.tx.isExpense.not() }.sumOf { it.tx.amountCents } }
    val balanceCents = monthIncome - monthExpense

    // 本月「花在哪了」：按分类聚合支出（未分类归为 null 一组），降序取前 3，
    // 用于一条横向占比条，比单纯列清单更有层次。
    val monthTop = remember(monthTx) {
        monthTx.nonTransfers().filter { it.tx.isExpense }
            .groupBy { it.category }
            .map { (cat, list) -> cat to list.sumOf { it.tx.amountCents } }
            .sortedByDescending { it.second }
            .take(3)
    }

    val recent = remember(all) { all.take(8) }
    val empty = all.isEmpty()

    // 预算：口径全部在 ui/budget 的纯函数里（转账剔除等），这里只管缓存与渲染。
    val budgetSummary = remember(budgets, all, thisMonth, nowMillis) {
        summarizeTotalBudget(budgets, all, thisMonth, nowMillis)
    }
    val budgetCatRows = remember(budgets, categories, all, thisMonth) {
        categoryBudgetRows(budgets, categories, all, thisMonth)
    }
    // 编辑弹层开合是首页自己的瞬时 UI 态，不需要 hoist —— 主控没有对应的 Overlay 枚举，
    // 且改 MainActivity 在禁区里。
    var showBudgetEditor by remember { mutableStateOf(false) }

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
            budgetSummary = budgetSummary,
            budgetCatRows = budgetCatRows,
            hasAnyBudget = budgets.isNotEmpty(),
            onEditBudget = { showBudgetEditor = true },
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
            budgetSummary = budgetSummary,
            budgetCatRows = budgetCatRows,
            hasAnyBudget = budgets.isNotEmpty(),
            onEditBudget = { showBudgetEditor = true },
        )
    }

    if (showBudgetEditor) {
        BudgetEditorDialog(
            budgets = budgets,
            categories = categories,
            monthKey = thisMonth,
            onDismiss = { showBudgetEditor = false },
            onSetBudget = onSetBudget,
            onRemoveBudget = onRemoveBudget,
        )
    }
}

// 「今天 / 本月」的边界统一走 data/DateRange.kt，不在各页面各写一份。

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
    budgetSummary: TotalBudgetSummary,
    budgetCatRows: List<CategoryBudgetRow>,
    hasAnyBudget: Boolean,
    onEditBudget: () -> Unit,
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
                    "¥ " + centsToYuan(balance),
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
                            "¥ " + centsToYuan(monthExpense),
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
                            "¥ " + centsToYuan(monthIncome),
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
                amount = centsToYuan(todayExpense),
                container = JizhangTheme.colors.expenseContainer,
                valueColor = JizhangTheme.colors.expense,
            )
            TodayBlock(
                modifier = Modifier.weight(1f),
                label = "今日收入",
                amount = centsToYuan(todayIncome),
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

        // 预算卡压在「最近账单」上方：先回答「还能花多少」，再看花在哪、花了什么。
        BudgetCard(
            summary = budgetSummary,
            categoryRows = budgetCatRows,
            hasAnyBudget = hasAnyBudget,
            onEdit = onEditBudget,
        )
        Spacer(Modifier.height(12.dp))

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
    budgetSummary: TotalBudgetSummary,
    budgetCatRows: List<CategoryBudgetRow>,
    hasAnyBudget: Boolean,
    onEditBudget: () -> Unit,
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
                "¥ " + centsToYuan(balance),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            // 半透明白底块（Color(0x1FFFFFFF) 为规范要求的「白字浅底」透明白），文字白。
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BlueMetricBlock(
                    modifier = Modifier.weight(1f),
                    label = "本月支出",
                    amount = centsToYuan(monthExpense),
                    container = Color(0x1FFFFFFF),
                    valueColor = Color.White,
                )
                BlueMetricBlock(
                    modifier = Modifier.weight(1f),
                    label = "本月收入",
                    amount = centsToYuan(monthIncome),
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
                    amount = centsToYuan(todayExpense),
                    container = JizhangTheme.colors.expenseContainer,
                    valueColor = JizhangTheme.colors.expense,
                )
                TodayBlock(
                    modifier = Modifier.weight(1f),
                    label = "今日收入",
                    amount = centsToYuan(todayIncome),
                    container = JizhangTheme.colors.incomeContainer,
                    valueColor = JizhangTheme.colors.income,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (monthTop.isNotEmpty()) {
                CategoryProportionCard(monthTop = monthTop)
                Spacer(Modifier.height(12.dp))
            }
            // 预算卡与 RecentCard 一样落在白色内容区上，BLUE/WHITE 两风格共用同一张卡。
            BudgetCard(
                summary = budgetSummary,
                categoryRows = budgetCatRows,
                hasAnyBudget = hasAnyBudget,
                onEdit = onEditBudget,
            )
            Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.width(6.dp))
        Text(
            "$year",
            style = MaterialTheme.typography.bodySmall,
            color = monthColor.copy(alpha = 0.55f),
        )
        Spacer(Modifier.weight(1f))
        // 22dp 图标直接 clickable 触控区过小（1.0.5 审计 P0）：用 IconButton 兜底到 48dp，
        // 视觉上图标仍是 22dp，只是可点范围变大。
        IconButton(
            onClick = onToggleStyle,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                JizhangIcons.Palette,
                contentDescription = "切换首页风格",
                tint = toggleTint,
                modifier = Modifier.size(22.dp),
            )
        }
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
                amount = centsToYuan(amt),
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

/**
 * 预算卡。两种首页风格共用：BLUE 风格里它和 RecentCard 一样落在白色内容区上，
 * 白底 surface + 语义色，不需要蓝顶下的透明白变体 —— 位置决定配色，而不是风格决定。
 *
 * 三态：
 *  - 一条预算都没有 → 单行引导，点整张卡（含引导行）开弹层；
 *  - 只有分类预算 → 不硬凑「今天还能花」（没有总额度可均摊），只列分类行；
 *  - 有总预算 → 大字今日额度 + 已花/可用进度条（超 100% 满条变红），下面可叠分类行。
 *
 * 所有数字由 ui/budget 的纯函数算好后传进来（转账剔除等口径全项目只有一份），
 * 这里只负责画。
 */
@Composable
private fun BudgetCard(
    summary: TotalBudgetSummary,
    categoryRows: List<CategoryBudgetRow>,
    hasAnyBudget: Boolean,
    onEdit: () -> Unit,
) {
    val onCard = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val expenseColor = JizhangTheme.colors.expense
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val onboarding = !summary.hasBudget && categoryRows.isEmpty()

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月预算",
                    style = MaterialTheme.typography.titleMedium,
                    color = onCard,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (hasAnyBudget) "调整" else "设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = faint,
                )
                Icon(
                    JizhangIcons.Edit,
                    contentDescription = null,
                    tint = faint,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.height(if (onboarding) 6.dp else 10.dp))

            if (onboarding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "设置月度预算，每天知道还能花多少",
                        style = MaterialTheme.typography.bodyMedium,
                        color = primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        JizhangIcons.ChevronRight,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                if (summary.hasBudget) {
                    // 负数按需求显示「今日已超支 ¥|X|」红色；X 仍是均摊到日的口径。
                    val overspent = summary.todayAllowanceCents < 0L
                    val allowanceColor = if (overspent) expenseColor else onCard
                    Text(
                        if (overspent) "今日已超支" else "今天还能花",
                        style = MaterialTheme.typography.bodySmall,
                        color = faint,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "¥ ",
                            style = MaterialTheme.typography.titleLarge,
                            color = allowanceColor,
                        )
                        Text(
                            centsToYuan(kotlin.math.abs(summary.todayAllowanceCents)),
                            style = MaterialTheme.typography.displaySmall,
                            color = allowanceColor,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 横条进度（不是圆环）：窄卡上圆环会把数字挤小，条 + 两端文案信息密度更高。
                    val fraction = if (summary.availableCents > 0L) {
                        (summary.spentCents.toFloat() / summary.availableCents.toFloat()).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    BudgetBar(
                        fraction = fraction,
                        fillColor = if (fraction >= 1f) expenseColor else primary,
                        trackColor = track,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已花 ¥${centsToYuan(summary.spentCents)} / 可用 ¥${centsToYuan(summary.availableCents)} · 剩 ${summary.remainingDays} 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = faint,
                    )
                }
                if (categoryRows.isNotEmpty()) {
                    if (summary.hasBudget) Spacer(Modifier.height(12.dp))
                    categoryRows.forEachIndexed { i, row ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        CategoryBudgetLine(
                            row = row,
                            expenseColor = expenseColor,
                            faint = faint,
                            track = track,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 迷你进度条：自绘 Box 而不是 LinearProgressIndicator ——
 * material3 1.2.1 的指示器带不确定动画语义且粗细不可控，
 * 与「本月花在哪」占比条同源的 Box 画法两条进度条才能像素级一致。
 */
@Composable
private fun BudgetBar(
    fraction: Float,
    fillColor: Color,
    trackColor: Color,
    height: Dp = 8.dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .background(trackColor),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.001f, 1f))
                    .fillMaxHeight()
                    .background(fillColor),
            )
        }
    }
}

@Composable
private fun CategoryBudgetLine(
    row: CategoryBudgetRow,
    expenseColor: Color,
    faint: Color,
    track: Color,
) {
    // 超支整行转红（数字 + 条），一眼扫列表时红是唯一的强信号，不再加图标噪音。
    val valueColor = if (row.overspent) expenseColor else faint
    val fillColor = if (row.overspent) expenseColor else Color(row.category.color)
    val fraction = (row.spentCents.toFloat() / row.amountCents.toFloat()).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.category.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "¥${centsToYuan(row.spentCents)} / ¥${centsToYuan(row.amountCents)}",
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
            )
        }
        Spacer(Modifier.height(3.dp))
        // 分类行用更细的条：与总预算条同形不同粗，层级靠尺寸区分。
        BudgetBar(fraction = fraction, fillColor = fillColor, trackColor = track, height = 5.dp)
    }
}

/** 最近账单分组标签：今天 / 昨天 / M月D日（跨年带年份）。 */
private fun recentDayLabel(millis: Long): String {
    val target = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    // 按「自然日序号」做差，避开夏令时/毫秒整除的坑；未来日期（补记）按今天处理。
    val dayDiff = ((today.timeInMillis - target.timeInMillis) / 86_400_000L).toInt()
    return when {
        dayDiff <= 0 -> "今天"
        dayDiff == 1 -> "昨天"
        target.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            String.format(Locale.CHINA, "%d月%d日", target.get(Calendar.MONTH) + 1, target.get(Calendar.DAY_OF_MONTH))
        else ->
            String.format(Locale.CHINA, "%d年%d月%d日", target.get(Calendar.YEAR), target.get(Calendar.MONTH) + 1, target.get(Calendar.DAY_OF_MONTH))
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
                // 日期分组头：「最近账单」经常横跨两三天，没有日期标签
                // 根本分不清哪笔是今天哪笔是前天（1.0.5 用户走查 P0）。
                var lastDay: String? = null
                recent.forEachIndexed { index, item ->
                    val day = recentDayLabel(item.tx.dateTime)
                    if (day != lastDay) {
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        Text(
                            day,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        lastDay = day
                    } else {
                        HorizontalDivider(color = dividerColor)
                    }
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
    // 转账行恒 isExpense=true，列表展示必须先看 isTransfer，否则会被画成红色"支出"。
    val transfer = tx.isTransfer
    val tint = when {
        transfer -> JizhangTheme.colors.transfer
        tx.isExpense -> JizhangTheme.colors.expense
        else -> JizhangTheme.colors.income
    }
    val container = when {
        transfer -> JizhangTheme.colors.transferContainer
        tx.isExpense -> JizhangTheme.colors.expenseContainer
        else -> JizhangTheme.colors.incomeContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenTx(item) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分类自身颜色是数据色，允许直接用；未分类回退到语义浅底。
        // 首字取「未分类」的「未」而非旧的「其」—— 和右侧文字标签统一（1.0.5 审计 P2）。
        val avatarBg = if (!transfer) cat?.let { Color(it.color) } ?: container else container
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (transfer) "转" else (cat?.name ?: "未分类").take(1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = readableOn(avatarBg),
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (transfer) "转账" else (cat?.name ?: "未分类"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tx.note.isNotBlank()) {
                Text(
                    tx.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            (if (transfer) "→ " else if (tx.isExpense) "-" else "+") + centsToYuan(tx.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}
