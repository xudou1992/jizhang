package com.jianji.jizhang.ui.budget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.BudgetEntity
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.nonTransfers
import com.jianji.jizhang.data.parseYuanToCents
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.util.Locale
import java.util.UUID

/**
 * 预算的「算法层」：全部是纯函数（不进 Compose、不读时钟单例、不碰 DAO），
 * 这样单测可以直接喂构造好的 budget/tx 列表验证结转、生效月份、超支口径，
 * 不需要 Robolectric，也不用等 VM 接线完成。
 *
 * 三条全项目统一的口径写在这里（而不是散进 HomeScreen）：
 *  1. 「已花」一律先剔转账再只算支出 —— 转账是左口袋进右口袋，
 *     计入预算等于转个账就把额度吃掉；
 *  2. 「生效」= startMonth <= 目标月，多条同 target 取 startMonth 最新的一条
 *     （与 VM 注释「逐月生效、不逐月复制行」对应）；yyyy-MM 定宽格式，
 *     字符串序 == 时间序，直接 compareTo 即可，不必解析；
 *  3. 结转只在「本月生效那条预算自己标了 carryOver」时发生，
 *     滚入额 = max(0, 上月可用 − 上月已花)，逐月向链前推进；
 *     链长封顶 [MAX_CARRY_CHAIN] 个月，防历史脏数据（startMonth 极早）
 *     让首页每次重组都空转几百个月。
 */

/** 同一 target 的预算行链最多向前追溯多少个月（10 年，远超真实使用场景）。 */
const val MAX_CARRY_CHAIN = 120

/** 总预算的 targetId 约定：空串 = 总预算（与 [BudgetEntity.targetId] 注释一致）。 */
const val TOTAL_TARGET_ID = ""

/* ---------------- 时间键 ---------------- */

/** epoch 毫秒 → "yyyy-MM"（设备默认时区，与 data/DateRange.kt 的月边界同一时区口径）。 */
fun monthKey(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return String.format(Locale.ROOT, "%04d-%02d", zoned.year, zoned.monthValue)
}

/** "yyyy-MM" 加减月份（跨年安全）。非法输入原样返回，UI 层不因此崩。 */
fun shiftMonthKey(key: String, delta: Int): String =
    runCatching { YearMonth.parse(key).plusMonths(delta.toLong()).toString() }
        .getOrDefault(key)

/** 本月还剩几天可花（含今天）。至少 1，除法分母不能是 0。 */
fun daysRemainingInMonth(millis: Long): Int {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return (date.lengthOfMonth() - date.dayOfMonth + 1).coerceAtLeast(1)
}

/* ---------------- 生效额度 / 结转 ---------------- */

/**
 * 对 [monthKey] 生效的那条预算行：同 target、金额>0、startMonth <= monthKey 中
 * startMonth 最新的一条。金额<=0 的行按「无预算」处理 —— VM 保证写不进去，
 * 但备份恢复等旁路可能漏进 0 额行，留在这里会画出「额度 0」的除零进度条。
 */
fun budgetRowFor(
    budgets: List<BudgetEntity>,
    targetId: String,
    monthKey: String,
): BudgetEntity? =
    budgets.filter { it.targetId == targetId && it.amountCents > 0L && it.startMonth <= monthKey }
        .maxByOrNull { it.startMonth }

/** 本月名义额度（分），不含结转。无生效预算返回 0。 */
fun effectiveBudget(budgets: List<BudgetEntity>, targetId: String, monthKey: String): Long =
    budgetRowFor(budgets, targetId, monthKey)?.amountCents ?: 0L

/**
 * 滚入 [monthKey] 的结转额（分）：本月生效行标了 carryOver 才有值，
 * = max(0, 上月可用 − 上月已花)，并沿链往前推（上月的可用又含它自己的结转）。
 *
 * [monthlySpend] 是同一 target 的「月 → 已花」表（用 [monthlySpendOf] 生成），
 * 传表而不是传账单列表：结转链要看很多历史月，一次分组、多次查询，
 * 也方便单测直接手搓 map。
 */
fun carriedOver(
    budgets: List<BudgetEntity>,
    targetId: String,
    monthKey: String,
    monthlySpend: Map<String, Long>,
): Long {
    val current = budgetRowFor(budgets, targetId, monthKey) ?: return 0L
    if (!current.carryOver) return 0L
    // yyyy-MM 定宽，字典序即时间序；链起点不早于 MAX_CARRY_CHAIN 个月前。
    val earliest = maxOf(current.startMonth, shiftMonthKey(monthKey, -MAX_CARRY_CHAIN))
    val previous = shiftMonthKey(monthKey, -1)
    var carry = 0L
    var m = earliest
    while (m <= previous) {
        val amount = effectiveBudget(budgets, targetId, m)
        carry = if (amount <= 0L) {
            0L // 预算停发的月份没有「可用额」可滚，链条在此清零
        } else {
            val included = budgetRowFor(budgets, targetId, m)?.carryOver == true
            val available = amount + if (included) carry else 0L
            (available - (monthlySpend[m] ?: 0L)).coerceAtLeast(0L)
        }
        // 备份旁路可能带进非法 startMonth（shiftMonthKey 对非法输入原样返回），
        // 步进失效就跳出，绝不让首页在重组里死循环。
        val next = shiftMonthKey(m, 1)
        if (next == m) break
        m = next
    }
    return carry
}

/** 本月真正可花的额度（分）= 名义额度 + 结转。 */
fun availableBudget(
    budgets: List<BudgetEntity>,
    targetId: String,
    monthKey: String,
    monthlySpend: Map<String, Long>,
): Long = effectiveBudget(budgets, targetId, monthKey) +
    carriedOver(budgets, targetId, monthKey, monthlySpend)

/* ---------------- 已花聚合 ---------------- */

/**
 * 某 target 每月已花（分）：key = "yyyy-MM"。
 * [targetId] 空串 = 总预算（所有非转账支出）；否则只统计该分类。
 * 转账与非支出（收入）一律剔除 —— 与预算语义一致的口径，全项目只此一份。
 */
fun monthlySpendOf(txs: List<TxWithCategory>, targetId: String): Map<String, Long> =
    txs.nonTransfers().asSequence()
        .filter { it.tx.isExpense }
        .filter { targetId.isEmpty() || it.tx.categoryId == targetId }
        .groupBy { monthKey(it.tx.dateTime) }
        .mapValues { entry -> entry.value.sumOf { it.tx.amountCents } }

/* ---------------- 今日额度 / 汇总 ---------------- */

/**
 * 今天还能花多少（分），可为负（负 = 本月已透支，卡片据此换成「今日已超支 ¥|X|」）。
 * 均摊到「含今天在内的剩余天数」。除不尽向零截断：正数少给不多给（宁可今天少花
 * 1 分，也不该明天才发现超了）；负数同理按 |X| 的整数部分显示，不放大透支额。
 */
fun todayAllowance(availableCents: Long, spentCents: Long, nowMillis: Long): Long =
    (availableCents - spentCents) / daysRemainingInMonth(nowMillis)

/** 总预算卡需要的全部数字，HomeScreen 一次算完。 */
data class TotalBudgetSummary(
    /** 本月是否有生效总预算（false 时其余字段无意义，卡片退化成引导/分类列表）。 */
    val hasBudget: Boolean,
    val availableCents: Long,
    val spentCents: Long,
    val todayAllowanceCents: Long,
    /** 本月剩余天数（含今天），给副标题「剩 N 天」用。 */
    val remainingDays: Int,
)

/** 聚合入口：总预算的可用 / 已花 / 今日可花。纯函数，单测主对象。 */
fun summarizeTotalBudget(
    budgets: List<BudgetEntity>,
    txs: List<TxWithCategory>,
    monthKey: String,
    nowMillis: Long,
): TotalBudgetSummary {
    val spend = monthlySpendOf(txs, TOTAL_TARGET_ID)
    val amount = effectiveBudget(budgets, TOTAL_TARGET_ID, monthKey)
    val available = amount + carriedOver(budgets, TOTAL_TARGET_ID, monthKey, spend)
    val spent = spend[monthKey] ?: 0L
    return TotalBudgetSummary(
        hasBudget = amount > 0L,
        availableCents = available,
        spentCents = spent,
        todayAllowanceCents = if (amount > 0L) todayAllowance(available, spent, nowMillis) else 0L,
        remainingDays = daysRemainingInMonth(nowMillis),
    )
}

/** 分类预算卡的一行。 */
data class CategoryBudgetRow(
    val category: CategoryEntity,
    val amountCents: Long,
    val spentCents: Long,
) {
    val overspent: Boolean get() = spentCents > amountCents
}

/**
 * 本月生效的分类预算行（已删分类的孤儿预算行直接跳过 —— VM 删分类会连带删预算，
 * 这里再防一道，避免备份恢复旁路漏进来自流出来的幽灵行）。
 * 排序：超支的排最前，其余按已花降序 —— 预算卡的意义是「哪冒警报了」，
 * 而不是复述分类管理页的 orderNum。
 *
 * 注意：分组聚合拆在 [categoryMonthlySpend]（参数名不叫 monthKey）里 —— 本函数
 * 参数 `monthKey: String` 会把同名的顶层 `fun monthKey(Long)` 遮掉，在它体内
 * 调 `monthKey(...)` 会编不过。
 */
fun categoryBudgetRows(
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    txs: List<TxWithCategory>,
    monthKey: String,
): List<CategoryBudgetRow> {
    val byCategory = categoryMonthlySpend(txs)
    return categories.mapNotNull { cat ->
        val amount = effectiveBudget(budgets, cat.id, monthKey)
        if (amount <= 0L) null
        else CategoryBudgetRow(cat, amount, byCategory[cat.id]?.get(monthKey) ?: 0L)
    }.sortedWith(
        // Boolean 不是 Comparable，超支标志先映射成 0/1 再比。
        compareByDescending<CategoryBudgetRow> { if (it.overspent) 1 else 0 }
            .thenByDescending { it.spentCents },
    )
}

/** 分类 id → (月 → 已花)。一次分组扫全表，别按分类数重复扫（608 笔 × N 个分类）。 */
private fun categoryMonthlySpend(txs: List<TxWithCategory>): Map<String, Map<String, Long>> =
    txs.nonTransfers().asSequence()
        .filter { it.tx.isExpense && it.tx.categoryId.isNotEmpty() }
        .groupBy { it.tx.categoryId }
        .mapValues { (_, rows) ->
            rows.groupBy { monthKey(it.tx.dateTime) }
                .mapValues { e -> e.value.sumOf { it.tx.amountCents } }
        }

/** 分 → 输入框文本。整数元不带小数点，非整数两位小数；不带千分位（编辑友好）。 */
fun centsToEditText(cents: Long): String =
    if (cents % 100L == 0L) (cents / 100L).toString()
    else String.format(Locale.CHINA, "%.2f", cents / 100.0)

/* ---------------- 编辑弹层 ---------------- */

/** 金额输入校验：最多 6 位整数 + 最多 2 位小数，空串合法（= 删除/不设）。 */
private val moneyPattern = Regex("""\d{0,6}(\.\d{0,2})?""")

private fun sanitizeMoney(raw: String): String =
    if (moneyPattern.matches(raw)) raw else ""

/**
 * 编辑目标行：优先取「本月生效」的那条；若该 target 只有未来 startMonth 的行
 * （理论上来自备份），也拿它改，避免另起一行造成同 target 双预算互相遮蔽。
 */
private fun editableRow(budgets: List<BudgetEntity>, targetId: String, monthKey: String) =
    budgetRowFor(budgets, targetId, monthKey)
        ?: budgets.filter { it.targetId == targetId }.maxByOrNull { it.startMonth }

/**
 * 预算编辑弹层。用 AlertDialog 而非 ModalBottomSheet：后者在本项目钉的
 * material3 1.2.1 里还是 @ExperimentalMaterial3Api，仓库里其它弹层（备份、账单）
 * 也一律 AlertDialog + 非实验 Switch/OutlinedTextField，风格与 API 稳定性统一。
 *
 * 交互模型：弹层内是草稿（本地文本态），点「完成」才逐条提交 —— 边输边写库会让
 * 输入到一半的 "1" 变成 1 元预算闪现；提交走 VM 的 setBudget（<=0 即删除），
 * 所以「清空输入框」就是删除该条。
 *
 * 约定：新建用随机 UUID、startMonth = 当前月；编辑保留原 id 与原 startMonth ——
 * startMonth 重置成当前月会让「本月生效」判定不变，但抹掉「从哪月起结转」的历史链，
 * 让用户改个金额就把以前月份的滚存悄悄改了，不可预期。
 * carryOver 只有总预算能开关；分类预算一律按 false 提交（需求：结转仅总预算支持），
 * 旧行上万一挂着的 true 会在下次编辑时被纠正掉。
 */
@Composable
fun BudgetEditorDialog(
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    monthKey: String,
    onDismiss: () -> Unit,
    onSetBudget: (id: String, targetId: String, amountCents: Long, carryOver: Boolean, startMonth: String) -> Unit,
    onRemoveBudget: (String) -> Unit,
) {
    val totalRow = remember(budgets, monthKey) { editableRow(budgets, TOTAL_TARGET_ID, monthKey) }
    var totalText by remember {
        mutableStateOf(totalRow?.amountCents?.let(::centsToEditText) ?: "")
    }
    var carryOver by remember { mutableStateOf(totalRow?.carryOver ?: false) }

    // 分类草稿：id → 文本。只存用户改过的与预填值，map 里没键 = 没预算。
    val catRows = remember(budgets, monthKey) {
        categories.mapNotNull { cat ->
            editableRow(budgets, cat.id, monthKey)?.let { cat.id to it }
        }
    }
    val catTexts = remember {
        mutableStateMapOf<String, String>().also { map ->
            catRows.forEach { (id, row) -> map[id] = centsToEditText(row.amountCents) }
        }
    }

    fun commit(targetId: String, row: BudgetEntity?, text: String, carry: Boolean) {
        val cents = parseYuanToCents(text)
        when {
            cents > 0L -> onSetBudget(
                // onSetBudget 是函数类型，位置传参（函数类型形参名不可用于命名实参）
                row?.id ?: UUID.randomUUID().toString(),
                targetId,
                cents,
                carry,
                row?.startMonth ?: monthKey,
            )
            // 文本清空/填 0 且原来有行 → 删除；本来就没有 → 什么都不做。
            row != null -> onRemoveBudget(row.id)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置预算") },
        text = {
            // 分类可能十几个，整列可滚动，弹层不会被撑出屏幕。
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = totalText,
                    onValueChange = { totalText = sanitizeMoney(it) },
                    label = { Text("总预算（元/月）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("结转结余", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "上月没花完滚入本月",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = carryOver, onCheckedChange = { carryOver = it })
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "分类预算（0 或清空 = 删除）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                if (categories.isEmpty()) {
                    Text(
                        "还没有分类，先去「分类管理」建几个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                categories.forEach { cat ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            cat.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = catTexts[cat.id] ?: "",
                            onValueChange = { catTexts[cat.id] = sanitizeMoney(it) },
                            label = { Text("元") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "预算从 $monthKey 起逐月生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                commit(TOTAL_TARGET_ID, totalRow, totalText, carryOver)
                categories.forEach { cat ->
                    commit(cat.id, catRows.firstOrNull { it.first == cat.id }?.second, catTexts[cat.id] ?: "", false)
                }
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 展示用：分 → 「¥x.xx」文案与首页其它卡片一致（centsToYuan），这里不重复造轮子。 */
fun yuanLabel(cents: Long): String = "¥" + centsToYuan(cents)
