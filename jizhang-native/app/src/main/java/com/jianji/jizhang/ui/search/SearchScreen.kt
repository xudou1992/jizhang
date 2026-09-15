package com.jianji.jizhang.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.jianji.jizhang.data.centsToYuan
import com.jianji.jizhang.data.isTransfer
import com.jianji.jizhang.ui.theme.readableOn
import com.jianji.jizhang.ui.theme.JizhangIcons
import com.jianji.jizhang.ui.theme.JizhangTheme
import kotlinx.coroutines.delay
import java.util.Locale

/** 纯数字查询（可带小数点尾巴，如 "5"、"15."、"12.30"）才参与金额匹配。 */
private val NUMERIC_QUERY = Regex("""\d+(\.\d*)?""")

/**
 * 金额文本命中判定。旧逻辑是 `centsToYuan(1500) == "15.00" contains "5"`，
 * 导致输「5」把 15.00、250.00 全捞出来。改为前缀式精确匹配：
 * "5" 命中 "5" / "5.xx"，"15" 命中 "15.00"，"15."/"15.0" 按前缀命中。
 * 先去掉千分位逗号再比：centsToYuan 带 "%,.2f" 分组符，否则 "1234" 匹配不上 "1,234.56"。
 */
private fun amountHit(display: String, q: String): Boolean {
    val s = display.replace(",", "")
    return s == q || s.startsWith("$q.") || (q.contains('.') && s.startsWith(q))
}

/**
 * 搜索页（整屏）：顶栏 + 吸顶搜索框 + 结果列表。
 * 列表用 [LazyColumn] 自行滚动，根 Column 不加 verticalScroll。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    all: List<TxWithCategory>,
    /** 检索逻辑目前不碰分类表；参数保留以兼容 MainActivity 调用点。 */
    categories: List<CategoryEntity>,
    /** 搜索词上提到 MainActivity：进详情再返回，查询不丢（1.0.5 用户走查）。 */
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenTx: (TxWithCategory) -> Unit,
    onBack: () -> Unit,
) {
    // 输入防抖 150ms：每个按键都全量扫 600 条 + 重组列表没必要。输入框仍即时回显
    // （value = query），只是「参与检索的词」延迟一拍。LaunchedEffect(query) 每收到
    // 新按键会取消上一轮未完成的 delay，天然就是「取消旧任务」的防抖。
    var settledQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        delay(150)
        settledQuery = query
    }
    val trimmed = settledQuery.trim()

    // 进页即聚焦弹键盘：搜索是「点开就要打字」的场景，不该多摸一次输入框。
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }

    // 匹配结果缓存：不要在每帧重算 600 条。all 本就按 dateTime 降序，filter 保序即可。
    // （去掉了此前 key 里没用的 categories 依赖：分类表变化不该让搜索结果失效重算。）
    val results = remember(all, trimmed) {
        if (trimmed.isEmpty()) {
            // 空关键词：展示最近 20 条
            all.take(20)
        } else {
            val lower = trimmed.lowercase(Locale.CHINA)
            // 纯数字查询才做金额精确匹配；文本查询只碰备注 / 分类名子串。
            val amountQuery = trimmed.takeIf { it.matches(NUMERIC_QUERY) }
            all.filter { item ->
                val note = item.tx.note
                val catName = item.category?.name ?: ""
                note.contains(lower, ignoreCase = true) ||
                    catName.contains(lower, ignoreCase = true) ||
                    (amountQuery != null && amountHit(centsToYuan(item.tx.amountCents), amountQuery))
            }
        }
    }

    // 合计支出 / 收入（仅非空关键词时显示）。nonTransfers()：转账恒 isExpense=true，
    // 不排除会把一笔还款计进「支出」，合计虚高。
    val totals = remember(results) {
        results.filter { !it.tx.isTransfer }.fold(0L to 0L) { (e, i), item ->
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
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus),
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
                    IconButton(onClick = { onQueryChange("") }) {
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
                    "支出 ¥" + centsToYuan(totalExpense),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = JizhangTheme.colors.expense,
                )
            }
            if (totalIncome > 0) {
                if (totalExpense > 0) Spacer(Modifier.width(12.dp))
                Text(
                    "收入 ¥" + centsToYuan(totalIncome),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = JizhangTheme.colors.income,
                )
            }
        }
    }
}

/**
 * 单条搜索结果行：与 BillListScreen.TxRow 同款。
 * 备注、分类名、金额三处都做命中关键词高亮（金额含 +/- 符号前缀一并匹配，
 * 展示串带千分位逗号时子串高亮可能不命中——属可接受的显示级误差）。
 */
@Composable
private fun SearchTxRow(
    item: TxWithCategory,
    keyword: String,
    onClick: () -> Unit,
) {
    val tx = item.tx
    val cat = item.category
    // 转账行恒 isExpense=true：先判转账，否则被画成红色"−支出"。
    val transfer = tx.isTransfer
    val hasCat = cat != null && !transfer
    // 有分类：分类色圆 + 按亮度取黑/白首字；转账用中性容器；无分类：次级填充底 + 「未」
    val avatarBg = when {
        transfer -> JizhangTheme.colors.transferContainer
        cat != null -> Color(cat.color)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val avatarText = if (transfer) "转" else cat?.name?.take(1) ?: "未"
    val avatarTextColor = readableOn(avatarBg)
    val amountColor = when {
        transfer -> JizhangTheme.colors.transfer
        tx.isExpense -> JizhangTheme.colors.expense
        else -> JizhangTheme.colors.income
    }
    // 高亮色先在 composable 作用域取好，再传入 buildAnnotatedString（非 composable lambda）。
    val highlightColor = MaterialTheme.colorScheme.primary
    val amountText = (if (transfer) "→ " else if (tx.isExpense) "-" else "+") + centsToYuan(tx.amountCents)

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
                text = buildHighlight(if (transfer) "转账" else (cat?.name ?: "未分类"), keyword, highlightColor),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (tx.note.isNotBlank()) {
                Text(
                    text = buildHighlight(tx.note, keyword, highlightColor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 右侧金额（数字查询命中时同样高亮）
        Text(
            text = buildHighlight(amountText, keyword, highlightColor),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = amountColor,
        )
    }
}

/**
 * 把任意展示文本里命中的关键词片段高亮（品牌蓝 + Medium）。
 * 备注 / 分类名 / 金额共用；颜色已在外层 composable 取好传入，本函数内不读 MaterialTheme。
 */
private fun buildHighlight(text: String, keyword: String, color: Color): AnnotatedString {
    if (keyword.isEmpty()) return AnnotatedString(text)
    val lowerText = text.lowercase(Locale.CHINA)
    val lowerKw = keyword.lowercase(Locale.CHINA)
    if (lowerKw !in lowerText) return AnnotatedString(text)
    val style = SpanStyle(color = color, fontWeight = FontWeight.Medium)
    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            val found = lowerText.indexOf(lowerKw, idx)
            if (found < 0) {
                append(text.substring(idx))
                break
            }
            if (found > idx) append(text.substring(idx, found))
            pushStyle(style)
            append(text.substring(found, found + keyword.length))
            pop()
            idx = found + keyword.length
        }
    }
}
