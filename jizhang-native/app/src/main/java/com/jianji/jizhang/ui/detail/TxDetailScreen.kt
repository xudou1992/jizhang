package com.jianji.jizhang.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import kotlin.math.roundToLong
import java.text.SimpleDateFormat
import java.util.Locale

/** 金额恒为「分」，统一用千分手点格式，避免各处分散写。 */
private fun money(cents: Long): String = String.format(Locale.CHINA, "%,.2f", cents / 100.0)

/** 查看态用的完整日期时间。 */
private val dtFmt = SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA)

/** 复制文本用的纯日期。 */
private val dateFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

/** 组装可复制的账单文本，例如「2026年9月14日 餐饮 -35.50 午饭」。 */
private fun buildCopyText(item: TxWithCategory): String {
    val tx = item.tx
    val catName = item.category?.name ?: "未分类"
    val sign = if (tx.isExpense) "-" else "+"
    val base = "${dateFmt.format(tx.dateTime)} $catName $sign${money(tx.amountCents)}"
    return if (tx.note.isNotBlank()) "$base ${tx.note}" else base
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxDetailScreen(
    item: TxWithCategory,
    categories: List<CategoryEntity>,
    onSave: (categoryId: String, isExpense: Boolean, amountCents: Long, note: String, dateTime: Long) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    // 编辑态用单一开关控制；进入编辑态即带着原值
    var editing by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    // 以 tx.id 为 key 初始化，切换账单或重新进入编辑都带原值
    var isExpense by remember(item.tx.id) { mutableStateOf(item.tx.isExpense) }
    var amountText by remember(item.tx.id) {
        mutableStateOf(String.format(Locale.CHINA, "%.2f", item.tx.amountCents / 100.0))
    }
    var note by remember(item.tx.id) { mutableStateOf(item.tx.note) }
    var categoryId by remember(item.tx.id) { mutableStateOf(item.tx.categoryId) }

    // 先把颜色提到 composable 作用域，避免在非 composable lambda 里读主题
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JizhangTheme.colors
    val accent = if (item.tx.isExpense) semantic.expense else semantic.income
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        // 顶栏：返回 / 标题 / 编辑 + 删除
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                JizhangIcons.ArrowBack,
                contentDescription = "返回",
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "账单详情",
                style = MaterialTheme.typography.titleLarge,
                color = colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Row {
                Icon(
                    JizhangIcons.Edit,
                    contentDescription = "编辑",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { editing = !editing },
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    JizhangIcons.Trash,
                    contentDescription = "删除",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { showDelete = true },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        if (!editing) {
            ViewMode(item = item, accent = accent, onEdit = { editing = true }, onCopy = {
                val text = buildCopyText(item)
                val cm = context.getSystemService(ClipboardManager::class.java) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("账单", text))
                Toast.makeText(context, "账单已复制到剪贴板", Toast.LENGTH_SHORT).show()
            })
        } else {
            EditMode(
                isExpense = isExpense,
                amountText = amountText,
                note = note,
                categoryId = categoryId,
                categories = categories,
                onIsExpense = { isExpense = it },
                onAmountText = { amountText = it },
                onNote = { note = it },
                onCategory = { categoryId = it },
                onSave = {
                    val cents = amountText.replace(",", "").toDoubleOrNull()
                        ?.let { (it * 100).roundToLong() } ?: 0L
                    // categoryId 恒非空（未分类是空串），只有金额需要校验。
                    if (cents > 0) {
                        onSave(categoryId, isExpense, cents, note, item.tx.dateTime)
                        editing = false
                    }
                },
            )
        }
    }

    // 删除二次确认
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条账单？") },
            text = {
                Text(
                    "${dateFmt.format(item.tx.dateTime)}  " +
                        (item.category?.name ?: "未分类") + "  " +
                        (if (item.tx.isExpense) "-" else "+") + money(item.tx.amountCents),
                    color = colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) { Text("删除", color = semantic.expense) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 查看态：大金额 + 信息卡片 + 两个按钮。 */
@Composable
private fun ViewMode(
    item: TxWithCategory,
    accent: Color,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val cat = item.category
    val tx = item.tx

    // 大号金额（displaySmall）
    Text(
        (if (tx.isExpense) "-" else "+") + money(tx.amountCents),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Medium,
        color = accent,
    )
    Spacer(Modifier.height(16.dp))

    // 信息卡片
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(JizhangTheme.colors.card)
            .padding(16.dp),
    ) {
        InfoRow(label = "分类") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(cat?.let { Color(it.color) } ?: Color(0xFF9CA0AB)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    cat?.name ?: "未分类",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onBackground,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = colorScheme.outline,
        )
        InfoRow(label = "日期时间") {
            Text(
                dtFmt.format(tx.dateTime),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onBackground,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = colorScheme.outline,
        )
        InfoRow(label = "备注") {
            Text(
                if (tx.note.isNotBlank()) tx.note else "无",
                style = MaterialTheme.typography.bodyMedium,
                color = if (tx.note.isNotBlank()) colorScheme.onBackground else colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(20.dp))

    Button(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
    ) {
        Text("编辑", fontSize = 16.sp)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onCopy,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Text("复制账单文本", fontSize = 16.sp, color = colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

/** 一行标签 + 内容的通用排布。 */
@Composable
private fun InfoRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 编辑态：类型切换 + 金额 + 分类网格 + 备注 + 保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMode(
    isExpense: Boolean,
    amountText: String,
    note: String,
    categoryId: String?,
    categories: List<CategoryEntity>,
    onIsExpense: (Boolean) -> Unit,
    onAmountText: (String) -> Unit,
    onNote: (String) -> Unit,
    onCategory: (String) -> Unit,
    onSave: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JizhangTheme.colors
    val cents = amountText.replace(",", "").toDoubleOrNull()?.let { (it * 100).roundToLong() } ?: 0L
    val canSave = cents > 0 && categoryId != null

    // 类型二段切换
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = isExpense,
            onClick = { onIsExpense(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors = SegmentedButtonDefaults.colors(
                activeContainerColor = semantic.expenseContainer,
                activeContentColor = semantic.expense,
            ),
        ) { Text("支出") }
        SegmentedButton(
            selected = !isExpense,
            onClick = { onIsExpense(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors = SegmentedButtonDefaults.colors(
                activeContainerColor = semantic.incomeContainer,
                activeContentColor = semantic.income,
            ),
        ) { Text("收入") }
    }
    Spacer(Modifier.height(20.dp))

    Text("金额", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = amountText,
        onValueChange = { v ->
            // 只留数字与一个小数点，小数最多 2 位
            val cleaned = v.filter { it.isDigit() || it == '.' }
            val parts = cleaned.split('.')
            onAmountText(
                (
                    if (parts.size <= 1) cleaned
                    else parts[0] + "." + parts.drop(1).joinToString("").take(2)
                    ).take(12),
            )
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("0.00") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
    Spacer(Modifier.height(20.dp))

    Text("分类", style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .height(((categories.size + 3) / 4 * 84).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false,
    ) {
        items(categories) { c ->
            val selected = categoryId == c.id
            Column(
                modifier = Modifier.clickable { onCategory(c.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(c.color))
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onCategory(c.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        c.name.take(1),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    c.name,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("备注（可选）") },
        singleLine = true,
    )
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onSave,
        enabled = canSave,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
    ) {
        Text("保存", fontSize = 16.sp)
    }
    Spacer(Modifier.height(24.dp))
}
