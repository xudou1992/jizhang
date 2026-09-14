package com.jianji.jizhang.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.util.Locale

/** 金额恒为「分」，统一用千分手点格式，避免各处分散写。 */
private fun money(cents: Long): String =
    String.format(Locale.CHINA, "%,.2f", cents / 100.0)

/**
 * 搜索页（整屏）：顶栏 + 吸顶搜索框 + 结果列表。
 * 列表用 [LazyColumn] 自行滚动，根 Column 不加 verticalScroll。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    all: List<TxWithCategory>,
    categories: List<CategoryEntity>,
    onOpenTx: (TxWithCategory) -> Unit,
    onBack: () -> Unit,
) {
    // 搜索关键词（本地状态）
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()

    // 匹配结果缓存：不要在每帧重算 600 条。all 本就按 dateTime 降序，filter 保序即可。
    val results = remember(all, categories, query) {
        if (trimmed.isEmpty()) {
            // 空关键词：展示最近 20 条
            all.take(20)
        } else {
            val lower = trimmed.lowercase(Locale.CHINA)
            // 关键词能解析成数字时，额外匹配金额格式化串
            val isNumber = trimmed.toDoubleOrNull() != null
            all.filter { item ->
                val note = item.tx.note
                val catName = item.category?.name ?: ""
                note.contains(lower, ignoreCase = true) ||
                    catName.contains(lower, ignoreCase = true) ||
                    (isNumber && money(item.tx.amountCents).contains(trimmed))
            }
        }
    }

    // 合计支出 / 收入（仅非空关键词时显示）
    val totals = remember(results) {
        results.fold(0L to 0L) { (e, i), item ->
            if (item.tx.isExpense) (e + item.tx.amountCents) to i
            else e to (i + item.tx.amountCents)
        }
    }
    val (totalExpense, totalIncome) = totals

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        // 顶栏：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    JizhangIcons.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                "搜索",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // 搜索框（吸顶）：备注 / 分类 / 金额
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索备注 / 分类 / 金额") },
            leadingIcon = {
                Icon(
                    JizhangIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            JizhangIcons.Close,
                            contentDescription = "清空",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Spacer(Modifier.height(8.dp))

        // 结果头
        ResultHeader(
            queryEmpty = trimmed.isEmpty(),
            count = results.size,
            totalExpense = totalExpense,
            totalIncome = totalIncome,
        )
        if (trimmed.isEmpty()) {
            // 空关键词提示
            Text(
                "输入关键词开始搜索",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (results.isEmpty()) {
            // 无结果：居中提示
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        JizhangIcons.Empty,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "没有匹配的账单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(
                    results,
                    key = { _, it -> it.tx.id },
                ) { index, item ->
                    SearchTxRow(
                        item = item,
                        keyword = trimmed,
                        onClick = { onOpenTx(item) },
                    )
                    // 行间细分隔线（末行不加）
                    if (index < results.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 44.dp),
                            color = MaterialTheme.colorScheme.outline,
                            thickness = 1.dp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** 结果头部：找到 N 条 / 最近 N 条 + 合计支出收入。 */
@Composable
private fun ResultHeader(
    queryEmpty: Boolean,
    count: Int,
    totalExpense: Long,
    totalIncome: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (queryEmpty) "最近 $count 条" else "找到 $count 条",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        if (!queryEmpty) {
            if (totalExpense > 0) {
                Text(
                    "支出 ¥" + money(totalExpense),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = JizhangTheme.colors.expense,
                )
            }
            if (totalIncome > 0) {
                if (totalExpense > 0) Spacer(Modifier.width(12.dp))
                Text(
                    "收入 ¥" + money(totalIncome),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = JizhangTheme.colors.income,
                )
            }
        }
    }
}

/** 单条搜索结果行：与 BillListScreen.TxRow 同款，备注命中关键词高亮。 */
@Composable
private fun SearchTxRow(
    item: TxWithCategory,
    keyword: String,
    onClick: () -> Unit,
) {
    val tx = item.tx
    val cat = item.category
    val hasCat = cat != null
    // 有分类：分类色圆 + 白字首字；无分类：次级填充底 + 「未」
    val avatarBg = cat?.let { Color(it.color) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    val avatarText = cat?.name?.take(1) ?: "未"
    val avatarTextColor = if (hasCat) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val amountColor = if (tx.isExpense) JizhangTheme.colors.expense else JizhangTheme.colors.income
    // 高亮色先在 composable 作用域取好，再传入 buildAnnotatedString（非 composable lambda）。
    val highlightColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧分类色圆
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                avatarText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = avatarTextColor,
            )
        }
        Spacer(Modifier.size(10.dp))
        // 中间：分类名 + 备注（单行省略，命中关键词高亮）
        Column(Modifier.weight(1f)) {
            Text(
                cat?.name ?: "未分类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (tx.note.isNotBlank()) {
                Text(
                    text = buildHighlightedNote(tx.note, keyword, highlightColor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 右侧金额
        Text(
            (if (tx.isExpense) "-" else "+") + money(tx.amountCents),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = amountColor,
        )
    }
}

/**
 * 把备注里命中的关键词片段高亮（品牌蓝 + Medium）。
 * 颜色已在外层 composable 取好传入，本函数内不再读 MaterialTheme。
 */
private fun buildHighlightedNote(note: String, keyword: String, color: Color): AnnotatedString {
    if (keyword.isEmpty()) return AnnotatedString(note)
    val lowerNote = note.lowercase(Locale.CHINA)
    val lowerKw = keyword.lowercase(Locale.CHINA)
    if (lowerKw !in lowerNote) return AnnotatedString(note)
    val style = SpanStyle(color = color, fontWeight = FontWeight.Medium)
    return buildAnnotatedString {
        var idx = 0
        while (idx < note.length) {
            val found = lowerNote.indexOf(lowerKw, idx)
            if (found < 0) {
                append(note.substring(idx))
                break
            }
            if (found > idx) append(note.substring(idx, found))
            pushStyle(style)
            append(note.substring(found, found + keyword.length))
            pop()
            idx = found + keyword.length
        }
    }
}
