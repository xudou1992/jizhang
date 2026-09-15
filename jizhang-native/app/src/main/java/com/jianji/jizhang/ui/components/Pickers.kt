package com.jianji.jizhang.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日 E", Locale.CHINA)

/** 本地毫秒 → 本地日期。 */
private fun localDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Material3 的 `selectedDateMillis` 是「UTC 当日 0 点」，要按 UTC 解读才是那个日历日。 */
private fun utcDayToLocalDate(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/** 本地日期 → Material3 需要的「UTC 当日 0 点」毫秒。 */
private fun localDateToUtcDay(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * 把「新选的日期」与「原时刻」合并：只换日期，保留原来的时分秒。
 *
 * 不这么做的话，改一笔昨天 21:30 的账的日期，时间会被抹成 00:00，
 * 排序和「今天/昨天」的分组会跟着乱。
 */
private fun mergeDate(oldMillis: Long, pickedUtcMillis: Long): Long {
    val time = Instant.ofEpochMilli(oldMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return utcDayToLocalDate(pickedUtcMillis)
        .atTime(time)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

/**
 * 日期选择行。点击弹出 Material3 日历。
 *
 * 这是「补记」的入口：此前全 App 没有任何改日期的地方，昨天的饭今天才想起来记，
 * 只能记成今天。`JizhangIcons.Calendar` 早就画好了，一直没人用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    dateTime: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hint = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { showPicker = true },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                JizhangIcons.Calendar,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                dayFmt.format(localDate(dateTime)),
                style = MaterialTheme.typography.bodyLarge,
                color = onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text("修改", style = MaterialTheme.typography.bodySmall, color = hint)
        }
    }

    if (showPicker) {
        val today = LocalDate.now()
        val todayUtcDay = localDateToUtcDay(today)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtcDay(localDate(dateTime)),
            // 记账不该记到未来：日历里今天之后的日期直接不可选。
            // 不加这道限制的话，未来日期的账会混进「本月 / 今日」的各种汇总里。
            selectableDates = remember(todayUtcDay, today.year) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        utcTimeMillis <= todayUtcDay

                    override fun isSelectableYear(year: Int): Boolean = year <= today.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(mergeDate(dateTime, it)) }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * 记账表单的类型枚举：原来的「支出｜收入」两段扩成三段，加上「转账」。
 * 用枚举而不是两个布尔，是因为 (isExpense, isTransfer) 能表示出
 * 「既是收入又是转账」这种非法组合，非法态在类型层面就该不存在。
 */
enum class TxBillType { EXPENSE, INCOME, TRANSFER }

/**
 * 三段类型切换条「支出｜收入｜转账」。
 *
 * AddScreen 与详情页的编辑表单各有一份收支两段，加转账时两处都要改；
 * 与其复制两份三段，不如沉到这里共用一份 —— 颜色、选中态、文案只有一个出处。
 * 转账不是消费也不是进账，用中性 [com.jianji.jizhang.ui.theme.SemanticColors.transfer]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxBillTypeSelector(
    selected: TxBillType,
    onSelect: (TxBillType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = JizhangTheme.colors
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selected == TxBillType.EXPENSE,
            onClick = { onSelect(TxBillType.EXPENSE) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            colors = SegmentedButtonDefaults.colors(
                activeContainerColor = semantic.expenseContainer,
                activeContentColor = semantic.expense,
            ),
        ) { Text("支出") }
        SegmentedButton(
            selected = selected == TxBillType.INCOME,
            onClick = { onSelect(TxBillType.INCOME) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            colors = SegmentedButtonDefaults.colors(
                activeContainerColor = semantic.incomeContainer,
                activeContentColor = semantic.income,
            ),
        ) { Text("收入") }
        SegmentedButton(
            selected = selected == TxBillType.TRANSFER,
            onClick = { onSelect(TxBillType.TRANSFER) },
            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            colors = SegmentedButtonDefaults.colors(
                activeContainerColor = semantic.transferContainer,
                activeContentColor = semantic.transfer,
            ),
        ) { Text("转账") }
    }
}

/**
 * 账户选择条。只有账户数 > 1 才显示 —— 只有一个账户时它没有意义，只是噪音。
 *
 * 此前账户管理是「半截功能」：能建多个账户，但记账时账户永远写死第一个，
 * 新建的账户永远收不到账单，余额恒为 0。
 */
@Composable
fun AccountSelector(
    accounts: List<AccountEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 转账的「转入」行：把转出账户排除后往往只剩 1 个候选，但它是**必选项**，
     * 不能沿用「单账户不显示」的防噪音规则 —— 否则两个账户之间反而转不了账。
     */
    showSingleOption: Boolean = false,
) {
    if (accounts.size <= 1 && !showSingleOption) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        accounts.forEach { account ->
            FilterChip(
                selected = account.id == selectedId,
                onClick = { onSelect(account.id) },
                label = { Text(account.name) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(account.color)),
                    )
                },
            )
        }
    }
}
