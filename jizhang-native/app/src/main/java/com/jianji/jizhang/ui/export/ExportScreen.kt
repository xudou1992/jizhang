package com.jianji.jizhang.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.ui.theme.JizhangTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.text.Charsets

private fun money(cents: Long): String =
    String.format(Locale.CHINA, "%,.2f", cents / 100.0)

private fun monthStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun escapeCsv(field: String): String {
    val needsQuote = field.contains(',') || field.contains('"') ||
        field.contains('\n') || field.contains('\r')
    return if (needsQuote) "\"" + field.replace("\"", "\"\"") + "\"" else field
}

private fun buildCsv(list: List<TxWithCategory>): String {
    val sb = StringBuilder()
    sb.append('\uFEFF')
    sb.append("日期,类型,分类,金额,备注\r\n")
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    for (item in list) {
        val tx = item.tx
        val dateStr = sdf.format(Date(tx.dateTime))
        val typeStr = if (tx.isExpense) "支出" else "收入"
        val catStr = item.category?.name ?: "未分类"
        val amtStr = String.format(Locale.CHINA, "%.2f", tx.amountCents / 100.0)
        val noteStr = tx.note
        val row = listOf(dateStr, typeStr, catStr, amtStr, noteStr)
            .joinToString(",") { escapeCsv(it) }
        sb.append(row).append("\r\n")
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(all: List<TxWithCategory>, onBack: () -> Unit) {
    val context = LocalContext.current
    var isMonth by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }

    val list = remember(all, isMonth) {
        if (isMonth) all.filter { it.tx.dateTime >= monthStartMillis() } else all
    }
    val expense = list.sumOf { if (it.tx.isExpense) it.tx.amountCents else 0L }
    val income = list.sumOf { if (!it.tx.isExpense) it.tx.amountCents else 0L }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = buildCsv(list)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            }
            val msg = "已导出 ${list.size} 笔账单"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            resultText = msg
        } catch (e: Exception) {
            val msg = "导出失败：${e.message ?: e.javaClass.simpleName}"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            resultText = msg
        }
    }

    Column(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("导出数据", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isMonth,
                onClick = { isMonth = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text("全部")
            }
            SegmentedButton(
                selected = isMonth,
                onClick = { isMonth = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text("本月")
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "共 ${list.size} 笔账单 · 支出 ¥${money(expense)} · 收入 ¥${money(income)}",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val name = "jianji-export-" +
                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".csv"
                launcher.launch(name)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = "导出 CSV",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "导出为 CSV 文件，可用 Excel 打开，已带 UTF-8 BOM 防止中文乱码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (resultText.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall,
                color = if (resultText.startsWith("已导出")) {
                    JizhangTheme.colors.income
                } else {
                    JizhangTheme.colors.expense
                }
            )
        }
    }
}
