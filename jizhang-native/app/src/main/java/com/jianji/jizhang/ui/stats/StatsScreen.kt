package com.jianji.jizhang.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

/** 金额格式：分 → 元，两位、千分位、中文语境 */
private fun money(cents: Long): String = String.format(Locale.CHINA, "%,.2f", cents / 100.0)

/** 单条分类聚合数据（环形图 / 排行共用） */
private data class Slice(
    val name: String,
    val color: Color,
    val amount: Long,
)

/** 单月趋势：短标签 + 当月支出/收入（单位：分）。 */
private data class MonthBar(
    val label: String,
    val expenseCents: Long,
    val incomeCents: Long,
)

/** 按月首/月末毫秒过滤；type=true 聚合支出，false 聚合收入 */
private fun aggregate(
    all: List<TxWithCategory>,
    startMs: Long,
    endMs: Long,
    isExpense: Boolean,
): List<Slice> {
    val map = LinkedHashMap<String, Slice>()
    for (item in all) {
        val tx = item.tx
        if (tx.dateTime < startMs || tx.dateTime > endMs) continue
        if (tx.isExpense != isExpense) continue
        val key = tx.categoryId.ifBlank { "__none" }
        val name = item.category?.name ?: "未分类"
        val color = item.category?.let { Color(it.color) } ?: Color(0xFF9CA0AB)
        val prev = map[key]
        map[key] = if (prev == null) {
            Slice(name, color, tx.amountCents)
        } else {
            prev.copy(amount = prev.amount + tx.amountCents)
        }
    }
    return map.values.sortedByDescending { it.amount }
}

/** 计算某年某月（month 为 Calendar 0 基）的首尾毫秒 */
private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val start = cal.timeInMillis
    cal.add(Calendar.MONTH, 1)
    cal.add(Calendar.MILLISECOND, -1)
    val end = cal.timeInMillis
    return start to end
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    all: List<TxWithCategory>,
    categories: List<CategoryEntity>,
) {
    // 当前年月（Calendar 0 基）
    val now = Calendar.getInstance()
    var year by remember { mutableIntStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(now.get(Calendar.MONTH)) }
    // 收支切换：true=支出，false=收入
    var isExpense by remember { mutableStateOf(true) }

    val (startMs, endMs) = remember(year, month) { monthRange(year, month) }

    // 当月全部账单
    val monthTx = remember(all, startMs, endMs) {
        all.filter { it.tx.dateTime in startMs..endMs }
    }
    // 当月汇总
    val expenseCents = remember(monthTx) {
        monthTx.filter { it.tx.isExpense }.sumOf { it.tx.amountCents }
    }
    val incomeCents = remember(monthTx) {
        monthTx.filter { it.tx.isExpense.not() }.sumOf { it.tx.amountCents }
    }
    val balanceCents = incomeCents - expenseCents

    // 分类聚合（按切换类型），最多前 5 + 其他
    val rawSlices = remember(all, startMs, endMs, isExpense) {
        aggregate(all, startMs, endMs, isExpense)
    }
    // 颜色必须先在这里（composable 上下文）取出来：remember 的 lambda 不是 @Composable，
    // 在里面读 MaterialTheme 会直接编译失败。
    val otherColor = MaterialTheme.colorScheme.outline
    val slices = remember(rawSlices, otherColor) {
        if (rawSlices.size > 6) {
            val top = rawSlices.take(5)
            val other = rawSlices.drop(5).sumOf { it.amount }
            top + Slice("其他", otherColor, other)
        } else {
            rawSlices
        }
    }
    val typeTotal = slices.sumOf { it.amount }
    val hasData = monthTx.isNotEmpty()

    // 近 6 个月趋势：以当前年月为终点，往前取 6 个自然月（含当月）。
    // remember 的 lambda 不是 @Composable，里面只算数、不读主题色；颜色在 TrendCard 里取。
    val months = remember(all, year, month) {
        // 先以当前月为终点构造 6 个 (年,0基月)，再 reverse 成「由远及近」
        val ym = ArrayList<Pair<Int, Int>>(6)
        var y = year
        var m = month
        repeat(6) {
            ym.add(y to m)
            m -= 1
            if (m < 0) { m = 11; y -= 1 }
        }
        ym.reverse()
        ym.map { (yy, mm) ->
            val (s, e) = monthRange(yy, mm)
            var exp = 0L
            var inc = 0L
            for (item in all) {
                val t = item.tx.dateTime
                if (t < s || t > e) continue
                if (item.tx.isExpense) exp += item.tx.amountCents else inc += item.tx.amountCents
            }
            MonthBar(String.format(Locale.CHINA, "%d月", mm + 1), exp, inc)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // 月份切换行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                JizhangIcons.ChevronLeft,
                contentDescription = "上个月",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable {
                        month -= 1
                        if (month < 0) { month = 11; year -= 1 }
                    },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                String.format(Locale.CHINA, "%d年%d月", year, month + 1),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                JizhangIcons.ChevronRight,
                contentDescription = "下个月",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable {
                        month += 1
                        if (month > 11) { month = 0; year += 1 }
                    },
            )
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // 支出 / 收入切换
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isExpense,
                onClick = { isExpense = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFFFCEBEB),
                    activeContentColor = JizhangTheme.colors.expense,
                ),
            ) { Text("支出") }
            SegmentedButton(
                selected = !isExpense,
                onClick = { isExpense = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFFEAF3EE),
                    activeContentColor = JizhangTheme.colors.income,
                ),
            ) { Text("收入") }
        }

        Spacer(Modifier.height(12.dp))

        // 汇总卡：支出 / 收入 / 结余
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = "支出",
                    amount = money(expenseCents),
                    color = JizhangTheme.colors.expense,
                )
                Box(
                    Modifier
                        .height(28.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = "收入",
                    amount = money(incomeCents),
                    color = JizhangTheme.colors.income,
                )
                Box(
                    Modifier
                        .height(28.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                SummaryItem(
                    modifier = Modifier.weight(1f),
                    label = "结余",
                    amount = money(balanceCents),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 近 6 个月趋势卡（置于汇总与环形图之间，承上启下）
        TrendCard(months = months)

        Spacer(Modifier.height(16.dp))

        // 环形图卡
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (isExpense) "支出构成" else "收入构成",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))
                // Canvas 的 lambda 不是 @Composable，颜色必须先在这里取出来。
                val emptyRingColor = MaterialTheme.colorScheme.surfaceVariant
                if (hasData && typeTotal > 0) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(184.dp)) {
                            val stroke = Stroke(width = 22f)
                            val gap = 1.5f
                            val inset = stroke.width / 2f + 2f
                            val arcSize = size.minDimension - inset * 2f
                            var start = -90f
                            for (s in slices) {
                                val sweep = (s.amount.toFloat() / typeTotal) * 360f
                                val drawn = max(0f, sweep - gap)
                                drawArc(
                                    color = s.color,
                                    startAngle = start,
                                    sweepAngle = drawn,
                                    useCenter = false,
                                    style = stroke,
                                    topLeft = Offset(inset, inset),
                                    size = Size(arcSize, arcSize),
                                )
                                start += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (isExpense) "总支出" else "总收入",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "¥ " + money(typeTotal),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income,
                            )
                        }
                    }
                } else {
                    // 空环 + 文案
                    Canvas(Modifier.size(184.dp)) {
                        val stroke = Stroke(width = 22f)
                        val inset = stroke.width / 2f + 2f
                        val arcSize = size.minDimension - inset * 2f
                        drawArc(
                            color = emptyRingColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = stroke,
                            topLeft = Offset(inset, inset),
                            size = Size(arcSize, arcSize),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这个月还没有记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 分类排行
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    "分类排行",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(12.dp))
                if (hasData && slices.isNotEmpty()) {
                    val maxAmount = slices.maxOf { it.amount }.toFloat().coerceAtLeast(1f)
                    slices.take(10).forEach { s ->
                        val pct = if (typeTotal > 0) (s.amount * 100 / typeTotal) else 0
                        RankingRow(
                            name = s.name,
                            color = s.color,
                            amount = money(s.amount),
                            percent = pct,
                            fraction = s.amount.toFloat() / maxAmount,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    Text(
                        "这个月还没有记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 趋势：近 6 个月收支柱状图
        Spacer(Modifier.height(16.dp))
        MonthlyTrendCard(all = all)

        // 明细：近 12 个月有数据的月份结余
        Spacer(Modifier.height(16.dp))
        YearBarsCard(all = all)

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * 近 6 个月趋势卡：Canvas 只画柱子，月份标签交给定等分的 Row，
 * 用 weight(1f) 保证 6 个标签与 6 组柱子中心对齐。
 * 颜色在 @Composable 作用域先取好，再闭包进 Canvas 的 draw lambda。
 */
@Composable
private fun TrendCard(
    months: List<MonthBar>,
) {
    val expenseColor = JizhangTheme.colors.expense
    val incomeColor = JizhangTheme.colors.income
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // 标题 + 图例（支出 / 收入）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "近 6 个月趋势",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(expenseColor))
                Spacer(Modifier.width(4.dp))
                Text("支出", style = MaterialTheme.typography.bodySmall, color = labelColor)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(incomeColor))
                Spacer(Modifier.width(4.dp))
                Text("收入", style = MaterialTheme.typography.bodySmall, color = labelColor)
            }

            Spacer(Modifier.height(12.dp))

            val hasTrend = months.any { it.expenseCents > 0L || it.incomeCents > 0L }
            if (hasTrend) {
                // 柱子：每月一组双柱（支出 + 收入），底端对齐。
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val chartH = size.height
                    val maxValue = months.maxOf { max(it.expenseCents, it.incomeCents) }
                        .toFloat().coerceAtLeast(1f)
                    val groupW = size.width / 6f
                    val barW = groupW * 0.30f
                    val innerGap = groupW * 0.10f
                    val radius = CornerRadius(6f, 6f)
                    months.forEachIndexed { i, mb ->
                        val centerX = i * groupW + groupW / 2f
                        val expH = (mb.expenseCents / maxValue) * chartH
                        val incH = (mb.incomeCents / maxValue) * chartH
                        val expX = centerX - barW - innerGap / 2f
                        val incX = centerX + innerGap / 2f
                        drawRoundRect(
                            color = expenseColor,
                            topLeft = Offset(expX, chartH - expH),
                            size = Size(barW, expH),
                            cornerRadius = radius,
                        )
                        drawRoundRect(
                            color = incomeColor,
                            topLeft = Offset(incX, chartH - incH),
                            size = Size(barW, incH),
                            cornerRadius = radius,
                        )
                    }
                }
            } else {
                // 6 个月全无数据：不画柱，给居中灰字
                Box(
                    Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = labelColor,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 月份标签：和柱子分组同样等分权重，自然对齐
            Row(Modifier.fillMaxWidth()) {
                months.forEach { mb ->
                    Text(
                        mb.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 汇总卡单项 */
@Composable
private fun SummaryItem(
    modifier: Modifier = Modifier,
    label: String,
    amount: String,
    color: Color,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            amount,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** 排行单行：色点 + 名称 + 金额/占比 + 进度条 */
@Composable
private fun RankingRow(
    name: String,
    color: Color,
    amount: String,
    percent: Long,
    fraction: Float,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$percent%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "¥ " + amount,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(6.dp))
        // 灰色槽 + 彩色填充条
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}
