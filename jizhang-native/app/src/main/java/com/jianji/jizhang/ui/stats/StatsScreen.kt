package com.jianji.jizhang.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import com.jianji.jizhang.ui.theme.NeutralAvatar
import java.util.Calendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

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

/**
 * 转账判据（本地副本）。
 * TODO(接线时换成 Ledger.kt 的 isTransfer)：该扩展属性随 toAccountId 在另一分支落地，
 * 提前引用会让本文件编译不过；行为等价 —— 转入账户非空即视为转账流水。
 */
private val TxWithCategory.isTransferTx: Boolean
    get() = tx.toAccountId.isNotBlank()

/**
 * 环形图扇区：start/full 是「名义」角度（含间隙占位，全部扇区的 full 之和恒为 360），
 * 命中检测用名义区间；drawn 是实际绘制角度（已扣间隙 / 已对小扇区兜底）。
 */
private data class RingSector(
    val start: Float,
    val full: Float,
    val drawn: Float,
)

/**
 * 扇区布局：绘制与点选共用这一份，单一来源 —— 两处各写各的必然漂移，
 * 出现「看着点的是 A 扇区、命中的却是 B」。
 */
private fun layoutRingSectors(slices: List<Slice>, total: Long, gapDeg: Float): List<RingSector> {
    val out = ArrayList<RingSector>(slices.size)
    var start = -90f
    for (s in slices) {
        val full = if (total > 0L) s.amount.toFloat() / total * 360f else 0f
        val drawn = if (full >= 3.6f) {
            // ≥1%（=3.6°）的扇区之间才留间隙。
            max(0f, full - gapDeg)
        } else {
            // <1% 的薄扇区不缩间隙、且给最小可见扫掠角兜底：
            // 旧算法 drawn = max(0, sweep - gap) 会把 0.4% 这类扇区直接抹成 0 ——
            // 「排行里有、环上看不见」比画一条细缝更糟。
            max(1.5f, full)
        }
        out += RingSector(start, full, drawn)
        start += full
    }
    return out
}

/** 环形几何（像素）。绘制与命中检测读同一份，否则半径边界会不一致。 */
private data class RingGeometry(
    val inset: Float,
    val arcSide: Float,
    val strokeBase: Float,
    val strokeExtra: Float,
) {
    val ringR: Float get() = arcSide / 2f
    val innerR: Float get() = ringR - strokeBase / 2f
    // 外沿把选中加粗的余量也算进来：点选中后凸出的那一条仍属该扇区（重点即取消）。
    val outerR: Float get() = ringR + strokeBase / 2f + strokeExtra
}

@Composable
private fun rememberRingGeometry(
    canvasSize: Dp,
    stroke: Dp,
    selectionExtra: Dp,
    margin: Dp,
): RingGeometry {
    val density = LocalDensity.current
    return remember(canvasSize, stroke, selectionExtra, margin, density) {
        with(density) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f + margin.toPx()
            val arcSide = canvasSize.toPx() - inset * 2f
            RingGeometry(inset, arcSide, strokePx, selectionExtra.toPx())
        }
    }
}

/** 按月首/月末毫秒过滤；type=true 聚合支出，false 聚合收入 */
private fun aggregate(
    all: List<TxWithCategory>,
    startMs: Long,
    endMs: Long,
    isExpense: Boolean,
): List<Slice> {
    val map = LinkedHashMap<String, Slice>()
    for (item in all) {
        // 剔除转账：转账是「左口袋进右口袋」，不属支出也不属收入；
        // 混进来会让环形中央总额与分类排行双计污染（转出去记一笔支出、转进来记一笔收入）。
        if (item.isTransferTx) continue
        val tx = item.tx
        if (tx.dateTime < startMs || tx.dateTime > endMs) continue
        if (tx.isExpense != isExpense) continue
        val key = tx.categoryId.ifBlank { "__none" }
        val name = item.category?.name ?: "未分类"
        val color = item.category?.let { Color(it.color) } ?: NeutralAvatar
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
    // 当月汇总：转账剔除后再求和。转账不是支出/收入，计入会虚增两侧流水
    val expenseCents = remember(monthTx) {
        monthTx.filter { !it.isTransferTx && it.tx.isExpense }.sumOf { it.tx.amountCents }
    }
    val incomeCents = remember(monthTx) {
        monthTx.filter { !it.isTransferTx && !it.tx.isExpense }.sumOf { it.tx.amountCents }
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

    // 环形图共享数据：绘制 / 点选 / 读屏三条链路都读这里，单一来源。
    // 环厚 22dp（旧值 22f 是裸像素：屏幕密度越高环显得越细），几何换算见 rememberRingGeometry。
    val ringGeom = rememberRingGeometry(
        canvasSize = 184.dp,
        stroke = 22.dp,
        selectionExtra = 6.dp,
        margin = 2.dp,
    )
    val density = LocalDensity.current
    // 间隙从「1.5 裸像素」换算成「1.5dp 弧长对应的圆心角」：环厚既然按 dp 走，
    // 间隙也要同一口径，否则低密度屏上间隙消失、高密度屏上豁大口子。
    val gapDeg = remember(ringGeom, density) {
        with(density) {
            Math.toDegrees((1.5.dp.toPx() / ringGeom.ringR).toDouble()).toFloat()
        }
    }
    val sectors = remember(slices, typeTotal, gapDeg) {
        layoutRingSectors(slices, typeTotal, gapDeg)
    }
    // 点选中的扇区（slices 下标，-1 = 未选中）。以 slices 为 key：
    // 切月份 / 切收支后扇区含义变了，旧下标会指向错误分类。
    var selectedIdx by remember(slices) { mutableIntStateOf(-1) }
    // 读屏摘要：环形是纯 Canvas，屏幕阅读器读不到任何信息，把切片拼成一句话。
    val ringDesc = remember(slices, typeTotal, isExpense) {
        buildString {
            append(if (isExpense) "分类占比图，总支出" else "分类占比图，总收入")
            append("¥${centsToYuan(typeTotal)}")
            slices.forEach { s ->
                val pct = if (typeTotal > 0L) (s.amount * 100 + typeTotal / 2) / typeTotal else 0L
                append("；${s.name}¥${centsToYuan(s.amount)}占${pct}%")
            }
        }
    }

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
                // 柱状图的月度聚合同样剔除转账：与环形/排行口径一致，否则切哪张卡看到的数都对不上
                if (item.isTransferTx) continue
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

        // 支出 / 收入切换。配色与 AddScreen / TxDetailScreen 走同一套语义令牌 ——
        // 此前这里和 AddScreen 都写死 #FCEBEB / #EAF3EE，只有详情页用了令牌，三页三色。
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isExpense,
                onClick = { isExpense = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = JizhangTheme.colors.expenseContainer,
                    activeContentColor = JizhangTheme.colors.expense,
                ),
            ) { Text("支出") }
            SegmentedButton(
                selected = !isExpense,
                onClick = { isExpense = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = JizhangTheme.colors.incomeContainer,
                    activeContentColor = JizhangTheme.colors.income,
                ),
            ) { Text("收入") }
        }

        Spacer(Modifier.height(12.dp))

        // 汇总卡：支出 / 收入 / 结余
        Card(
            shape = MaterialTheme.shapes.large,
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
                    amount = centsToYuan(expenseCents),
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
                    amount = centsToYuan(incomeCents),
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
                    amount = centsToYuan(balanceCents),
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
            shape = MaterialTheme.shapes.large,
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
                        Canvas(
                            Modifier
                                .size(184.dp)
                                .semantics { contentDescription = ringDesc }
                                .pointerInput(sectors, ringGeom) {
                                    detectTapGestures { off ->
                                        // 命中判定：把点击坐标换算成「相对圆心的极坐标」。
                                        // PointerInputScope.size 与 Canvas 布局尺寸一致（184dp 定宽），
                                        // 所以圆心取画布中心即可与绘制用的 arc 圆同心。
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val dx = off.x - cx
                                        val dy = off.y - cy
                                        val r = sqrt(dx * dx + dy * dy)
                                        // 只有落在环带（内径~外径）里才算命中扇区；
                                        // 圆盘内部、环外、以及完全空白处都按「点空白」处理 → 取消选中。
                                        if (r < ringGeom.innerR || r > ringGeom.outerR) {
                                            selectedIdx = -1
                                            return@detectTapGestures
                                        }
                                        // atan2 在 y 轴向下的屏幕坐标系里，角度天然沿顺时针增长，
                                        // 与 drawArc 的正方向一致；归一到 [0,360) 后按名义区间比较。
                                        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                        if (deg < 0f) deg += 360f
                                        var hit = -1
                                        for ((i, sec) in sectors.withIndex()) {
                                            var s = sec.start % 360f
                                            if (s < 0f) s += 360f
                                            var d = deg
                                            if (d < s) d += 360f // 处理跨 0° 的扇区
                                            if (d < s + sec.full) { hit = i; break }
                                        }
                                        // 再点同一扇区 = 取消；点中别的扇区 = 切换
                                        selectedIdx = if (hit >= 0 && hit != selectedIdx) hit else -1
                                    }
                                },
                        ) {
                            // 扇区角来自 layoutRingSectors —— 与上面的点选共用同一函数，绘制顺序即命中顺序
                            for ((i, sec) in sectors.withIndex()) {
                                // 选中扇区加粗 6dp：不动半径、只增线宽，扇区角不受影响（几何仍走 ringGeom 常量）
                                val w = ringGeom.strokeBase +
                                    if (i == selectedIdx) ringGeom.strokeExtra else 0f
                                drawArc(
                                    color = slices[i].color,
                                    startAngle = sec.start,
                                    sweepAngle = sec.drawn,
                                    useCenter = false,
                                    style = Stroke(width = w),
                                    topLeft = Offset(ringGeom.inset, ringGeom.inset),
                                    size = Size(ringGeom.arcSide, ringGeom.arcSide),
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val sel = slices.getOrNull(selectedIdx)
                            if (sel != null) {
                                // 选中态：中央从「总额」切换为「分类名 + 金额 + 占比」。
                                // 占比与排行行同一四舍五入口径（+total/2），两处数字必须对得上。
                                val pct = (sel.amount * 100 + typeTotal / 2) / typeTotal
                                Text(
                                    sel.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "¥ " + centsToYuan(sel.amount),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income,
                                )
                                Text(
                                    "占 $pct%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    if (isExpense) "总支出" else "总收入",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "¥ " + centsToYuan(typeTotal),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income,
                                )
                            }
                        }
                    }
                } else {
                    // 空环 + 文案
                    Canvas(Modifier.size(184.dp)) {
                        drawArc(
                            color = emptyRingColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = ringGeom.strokeBase),
                            topLeft = Offset(ringGeom.inset, ringGeom.inset),
                            size = Size(ringGeom.arcSide, ringGeom.arcSide),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 文案跟着收支页签：有支出没收入时，切到「收入」不该说「还没有记录」。
                    Text(
                        if (isExpense) "这个月还没有支出" else "这个月还没有收入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 分类排行
        Card(
            shape = MaterialTheme.shapes.large,
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
                if (slices.isNotEmpty()) {
                    val maxAmount = slices.maxOf { it.amount }.toFloat().coerceAtLeast(1f)
                    slices.take(10).forEach { s ->
                        // 四舍五入而非整除截断：原来 0.9% 显示成 0%（审计 P2）。
                        val pct = if (typeTotal > 0) (s.amount * 100 + typeTotal / 2) / typeTotal else 0L
                        RankingRow(
                            name = s.name,
                            color = s.color,
                            amount = centsToYuan(s.amount),
                            percent = pct,
                            fraction = s.amount.toFloat() / maxAmount,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    Text(
                        if (isExpense) "这个月还没有支出" else "这个月还没有收入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 明细：近 12 个月有数据的月份结余。
        // 原来这里还挂着一张 MonthlyTrendCard（固定锚定「当前月」的近 6 个月柱状图），
        // 与上方跟随月份选择器的 TrendCard 是同一件事的两种实现 —— 用户切到 3 月时
        // 一张跟着变、一张不动，只会让人以为数据错了。已删除，只留跟随选择器的那张。
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

    // 柱图点选：选中组的下标，-1 = 未选中。以 months 为 key —— 切月份后 6 组柱子整体平移，
    // 旧下标会指着另一个月的柱子。状态放卡片内部，不外泄、也不动公开签名。
    var selectedBar by remember(months) { mutableIntStateOf(-1) }

    // 读屏摘要：柱图是纯 Canvas，读屏软件看不到任何数据，把 6 组数拼成一句话
    val barsDesc = remember(months) {
        buildString {
            append("近6个月趋势")
            months.forEach { mb ->
                append("；${mb.label}支出¥${centsToYuan(mb.expenseCents)}收入¥${centsToYuan(mb.incomeCents)}")
            }
        }
    }

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
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .semantics { contentDescription = barsDesc }
                        .pointerInput(months) {
                            detectTapGestures { off ->
                                if (months.isEmpty()) return@detectTapGestures
                                // 与绘制同一套等分几何：x 落在第 i 组 [i*groupW,(i+1)*groupW) 即命中该月。
                                // 不挑柱体本身 —— 空白处也算点中整列，小屏上才按得动。
                                val groupW = size.width / months.size.toFloat()
                                val idx = (off.x / groupW).toInt()
                                if (idx !in months.indices) return@detectTapGestures
                                selectedBar = if (idx == selectedBar) -1 else idx
                            }
                        },
                ) {
                    val chartH = size.height
                    val maxValue = months.maxOf { max(it.expenseCents, it.incomeCents) }
                        .toFloat().coerceAtLeast(1f)
                    val groupW = size.width / 6f
                    val barW = groupW * 0.30f
                    val innerGap = groupW * 0.10f
                    val radius = CornerRadius(6f, 6f)
                    months.forEachIndexed { i, mb ->
                        val centerX = i * groupW + groupW / 2f
                        // 选中月垫一层淡色底：点一下总得在图上有个反馈，光靠下方文字不够直观
                        if (i == selectedBar) {
                            drawRoundRect(
                                color = labelColor.copy(alpha = 0.12f),
                                topLeft = Offset(i * groupW + groupW * 0.04f, 0f),
                                size = Size(groupW * 0.92f, chartH),
                                cornerRadius = CornerRadius(8f, 8f),
                            )
                        }
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

            // 选中月的数字明细：柱高只能看个大概，金额得靠文字兜底
            months.getOrNull(selectedBar)?.let { mb ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${mb.label}：支出 ¥${centsToYuan(mb.expenseCents)} · 收入 ¥${centsToYuan(mb.incomeCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
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
