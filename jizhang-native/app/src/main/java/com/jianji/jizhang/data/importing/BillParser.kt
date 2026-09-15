package com.jianji.jizhang.data.importing

import com.jianji.jizhang.data.TxDraft
import com.jianji.jizhang.data.parseYuanToCents
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 账单导入解析器：微信 / 支付宝 / 简记自己导出的 CSV → 内存里的 [ImportedTx] 列表。
 *
 * 为什么单独成包、而且**刻意保持纯 JVM**（只依赖 java.* 与同包的纯函数，
 * 不碰 Context / Room / Compose）：
 *   1) 解析是整条功能里最容易出错、也最容易验证的一段 —— 三家 CSV 的列名、编码、
 *      退款口径全不一样。纯 JVM 意味着 app/src/test 里几行断言就能永久拦住回归，
 *      不需要模拟器（本项目的单测一直是这个路子：MoneyTest / BackupNamingTest）。
 *   2) 与 ViewModel 解耦：解析只吐数据，落库由调用方决定。
 *   （这里只 import 了 [TxDraft] 与 [parseYuanToCents] —— 两者本身都是无 Android 依赖的纯文件，
 *    复用它们才不会把「元→分」的换算写出第二份实现。）
 *
 * 刻意不做的事：
 *   · 不做「智能猜分类」：微信的交易类型和简记的分类基本对不上，硬猜只会把 45 块午饭
 *     记成「交通出行」。账单自带的分类名原样带出去（[ImportedTx.categoryHint]），
 *     由界面按名字精确匹配，匹不上 = 未分类，用户事后可改。
 *   · 不做去重：那要拿全库比对，属于 ViewModel；重复导入由用户在预览里自己判断。
 *   · 不做转账还原：见文件末尾 [parseJizhangBill] 与 SkipTally.transfer 的说明。
 */

/** 账单来源格式。[label] 直接给界面当芯片/徽章文案。 */
enum class BillFormat(val label: String) {
    WECHAT("微信"),
    ALIPAY("支付宝"),
    JIZHANG("简记"),
    UNKNOWN("未识别"),
}

/**
 * 解析出来的「一行账单」。还没落到简记的数据模型上：没有账户、没有分类 id ——
 * 一份微信账单里既有零钱也有信用卡，解析器没能力替用户猜它该进哪个账户，
 * 必须由人在下一步选一个目标账户。
 */
data class ImportedTx(
    /** 交易时刻（系统时区的 epoch 毫秒）。 */
    val millis: Long,
    /** true = 支出。方向只由这一个字段表达，[yuanText] 恒为绝对值。 */
    val expense: Boolean,
    /** 「元」的文本：已去掉货币符号/千分位/正负号，可直接喂给 [parseYuanToCents]。 */
    val yuanText: String,
    /** 交易对方（简记导出没有这列，那个位置放的是账户名，仅供预览）。 */
    val counterparty: String,
    /** 账单自带的分类名（微信=交易类型，支付宝=交易分类，简记=分类列）。可能为空。 */
    val categoryHint: String,
    /** 商品/说明/状态/备注拼出来的备注。 */
    val note: String,
)

/**
 * 可以一步落库的导入草稿。
 *
 * 为什么不直接复用 [TxDraft]：那样 ImportScreen 就得知道 accountId、随机 id 这些
 * 只有 ViewModel 才该决定的东西。这里只带「解析能确定的部分 + 已解析出的 categoryId」，
 * 补账户、生成 id 由调用方做（见 [toTxDraft]）。
 *
 * ⚠️ [categoryName] 与 [categoryId] 的区别是 onImport 契约的核心，别混用：
 * 前者是账单里读到的**文本**（「商户消费」「餐饮美食」），只供展示与日志；
 * 后者是界面用传入的分类表按名字**精确匹配**出来的 id，匹配不上 = 空串 = 未分类。
 * 落库一律认 [categoryId]。
 */
data class ImportedTxDraft(
    val millis: Long,
    val expense: Boolean,
    val amountCents: Long,
    val categoryName: String,
    val note: String,
    /** 已解析的分类 id；空串 = 未分类。带默认值 → 五参数位置构造依然合法。 */
    val categoryId: String = "",
    /** 用户在预览页选的目标账户 id。空串 = 调用方自己兜底（如 ViewModel 的首个账户）。 */
    val accountId: String = "",
)

/**
 * [ImportedTxDraft] → ViewModel 的入参。
 * 账户默认取草稿里自带的 [ImportedTxDraft.accountId]（= 预览页选的那个），
 * 调用方也可以显式覆盖。转账（toAccountId）不由导入产生，故默认空串；
 * 金额恒 > 0 由界面保证（addTx 对 amountCents<=0 是静默返回的，导进 0 元行只会悄悄少几笔）。
 */
fun ImportedTxDraft.toTxDraft(accountId: String = this.accountId, toAccountId: String = ""): TxDraft = TxDraft(
    categoryId = categoryId,
    accountId = accountId,
    isExpense = expense,
    amountCents = amountCents,
    note = note,
    dateTime = millis,
    toAccountId = toAccountId,
)

/* ---------------- 入口 ---------------- */

/**
 * 按**表头文本**判定格式。传文件开头 2~4KB 足够（表头之前只有几十行元数据）。
 *
 * 判定顺序有讲究：简记的表头最特殊先排掉；微信与支付宝都有「交易时间」「交易对方」，
 * 唯一稳定的区分点是微信有「交易类型」、支付宝有「交易分类」。
 */
fun detectFormat(headerText: String): BillFormat {
    val s = headerText.removePrefix(BOM)
    fun has(vararg keys: String) = keys.any { s.contains(it) }
    return when {
        has("日期") && has("账户") && has("类型") && has("分类") -> BillFormat.JIZHANG
        has("微信支付账单", "交易类型") -> BillFormat.WECHAT
        has("支付宝交易明细", "交易分类", "收入金额") -> BillFormat.ALIPAY
        // 只剩「交易时间 + 收/支 + 交易对方」的残缺表头：支付宝必带「交易分类」或收支两列，
        // 所以这种更像微信。
        has("交易时间") && has("收/支") && has("交易对方") -> BillFormat.WECHAT
        else -> BillFormat.UNKNOWN
    }
}

/** 已解码文本 → 指定格式的解析结果。[BillFormat.UNKNOWN] 给可读失败原因。 */
fun parseBill(text: String, format: BillFormat): Result<List<ImportedTx>> = when (format) {
    BillFormat.WECHAT -> parseWechatBill(text)
    BillFormat.ALIPAY -> parseAlipayBill(text)
    BillFormat.JIZHANG -> parseJizhangBill(text)
    BillFormat.UNKNOWN -> failed(
        "无法识别账单格式：开头既没有微信/支付宝的「交易时间」表头，也没有简记的「日期,账户,类型…」表头。\n" +
            "常见原因：选成了 xlsx / PDF（只支持 CSV 文本）、或者那是银行流水模板。",
    )
}

/**
 * 账单解析器：字节 → 文本 → 记录。
 *
 * [charset] 是留给「自动判定选错编码」的出口：默认走 UTF-8 → GB18030 的自动解码链，
 * 只有拿到编码确定的文件（自己脚本转出来的）才显式传。
 * 构造不抛异常（空文件也照常构造），所有失败都走 [parse] 的 Result。
 */
class BillParser(
    private val bytes: ByteArray,
    private val charset: Charset? = null,
) {
    /** 实际参与解码的字符集。出错文案会带上它 —— 读错编码时这是第一现场线索。 */
    val usedCharset: Charset

    /** 解码后的全文（已去掉 UTF-8 BOM）。 */
    val text: String

    init {
        val (decoded, cs) = decode(bytes, charset)
        text = decoded
        usedCharset = cs
    }

    /** 取开头一段做格式判定；表头之前的元数据都在这段里。 */
    val headerText: String get() = text.substring(0, minOf(text.length, 4000))

    /** 表头关键词判定的结果。UNKNOWN = 认不出来，但不代表一定解析不了。 */
    val detected: BillFormat by lazy { detectFormat(headerText) }

    /**
     * 解析。识别成功时**以识别结果为准**，[preferred] 只在识别失败时兜底 ——
     * 用户在这一步选错来源太常见（微信和支付宝的账单都在邮件里，文件名都叫 csv），
     * 而表头比人的记忆可靠。两者不一致时界面要亮徽章说明到底按什么解的。
     */
    fun parse(preferred: BillFormat = detected): Result<List<ImportedTx>> {
        if (text.isBlank()) return failed("文件里没有内容（0 字节，或只有一串空白）。")
        val format = if (detected != BillFormat.UNKNOWN) detected else preferred
        return parseBill(text, format).map { rows ->
            // 统一按时间升序返回：三家的默认顺序不一样（微信/简记都是新→旧），
            // 排完预览列表和导入顺序才稳定，用户逐笔核对时不会上下跳。
            rows.sortedBy { row -> row.millis }
        }
    }
}

/* ---------------- 解码 ---------------- */

private const val BOM = "\uFEFF"

/**
 * GB18030（GBK 的超集）。个别运行环境只登记了 GBK，取不到就退一步 ——
 * 用 GB18030 解 GBK 是安全的，反过来不是。
 */
val GB18030: Charset = runCatching { Charset.forName("GB18030") }
    .getOrElse { Charset.forName("GBK") }

private fun decode(bytes: ByteArray, forced: Charset?): Pair<String, Charset> {
    if (forced != null) return bytes.toString(forced).stripBom() to forced
    val utf8 = tryDecodeUtf8(bytes)
    if (utf8 != null && !looksMisdecoded(utf8)) return utf8.stripBom() to StandardCharsets.UTF_8
    val gb = bytes.toString(GB18030).stripBom()
    if (utf8 == null) return gb to GB18030
    // 罕见：两种编码都能解出「文本」。谁汉字多谁对。
    return if (countCjk(gb) > countCjk(utf8)) {
        gb to GB18030
    } else {
        utf8.stripBom() to StandardCharsets.UTF_8
    }
}

/** 严格 UTF-8：非法字节序列直接失败，而不是悄悄换成替换符。 */
private fun tryDecodeUtf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    null
}

private fun String.stripBom(): String = if (startsWith(BOM)) substring(BOM.length) else this

private fun countCjk(s: String): Int {
    var n = 0
    for (c in s) if (c in '\u4E00'..'\u9FFF') n++
    return n
}

/**
 * 「解出来是文本，但八成读错了编码」的判据。
 *
 * 为什么不能只看 UTF-8 解码抛不抛异常：GBK 字节硬转 UTF-8 时不一定报错，
 * 可能配出几个合法但无意义的码位。所以要正向找乱码特征：
 *   · 出现替换符 U+FFFD 或私有区码位 —— 铁定错；
 *   · 有非 ASCII 字节却一个汉字都没有 —— 中文账单里没中文才是异常。
 */
private fun looksMisdecoded(text: String): Boolean {
    if (text.isEmpty()) return false
    for (c in text) {
        if (c == '\uFFFD') return true
        if (c in '\uE000'..'\uF8FF') return true
    }
    if (!text.any { it.code > 127 }) return false
    return countCjk(text) == 0
}

/* ---------------- CSV 记录读取 ---------------- */

/** 一条 CSV 记录：字段表 + 下一条记录的起始下标。 */
class CsvLine internal constructor(val fields: List<String>, val next: Int)

/**
 * 从 [start] 起读**一条记录**（不是一物理行）。
 *
 * 为什么不能用 `text.lines()`：微信的「商品」、支付宝的「说明」里常有引号包住的
 * 逗号和换行（外卖订单标题最常见），按 \n 切会把一条账单劈成两条垃圾，
 * 第二条还会把后面的列序全部污染。
 */
fun readCsvLine(text: String, start: Int, delimiter: Char = ','): CsvLine? {
    if (start >= text.length) return null
    val fields = ArrayList<String>(12)
    val sb = StringBuilder()
    var i = start
    var quoted = false
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            when (c) {
                '"' ->
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"')
                        i += 2 // "" = 字段内容里的一个引号
                    } else {
                        quoted = false
                        i++
                    }
                // 引号内的换行统一成 \n：留着 \r 会让备注在界面里多出一行空白
                '\r' -> {
                    sb.append('\n')
                    i++
                    if (i < text.length && text[i] == '\n') i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
            continue
        }
        when (c) {
            '"' -> { quoted = true; i++ }
            delimiter -> { fields.add(sb.toString().trim()); sb.setLength(0); i++ }
            '\n' -> { fields.add(sb.toString().trim()); return CsvLine(fields, i + 1) }
            '\r' -> {
                fields.add(sb.toString().trim())
                i++
                if (i < text.length && text[i] == '\n') i++
                return CsvLine(fields, i)
            }
            else -> { sb.append(c); i++ }
        }
    }
    // 末行没有换行符的收尾（不少导出工具就是如此）
    fields.add(sb.toString().trim())
    return CsvLine(fields, i)
}

/* ---------------- 表头定位 ---------------- */

/** 表头之前的元数据行数上限：微信约 17 行、支付宝约 5 行；80 行还没有就是选错文件了。 */
private const val MAX_HEADER_SCAN = 80

private class BillTable(val header: List<String>, val rows: List<List<String>>)

/** 微信/支付宝/简记共用：从开头扫，第一条以 [keyColumn] 起头的记录就是表头。 */
private fun readBillTable(text: String, keyColumn: String): BillTable? {
    val rows = ArrayList<List<String>>()
    val key = normKey(keyColumn)
    var pos = 0
    var header: List<String>? = null
    var scanned = 0
    while (pos < text.length) {
        val line = readCsvLine(text, pos) ?: break
        pos = line.next
        if (header == null) {
            if (++scanned > MAX_HEADER_SCAN) return null
            if (line.fields.firstOrNull()?.let { normKey(it) } == key) header = line.fields
            continue
        }
        // 微信/支付宝末尾挂着「共 123 笔」的统计行：按内容截断，别让它参与解析
        if (isSummaryLine(line.fields)) break
        rows.add(line.fields)
    }
    return header?.let { BillTable(it, rows) }
}

private val SUMMARY_RE = Regex("""^共\s*\d+\s*笔""")

private fun isSummaryLine(fields: List<String>): Boolean =
    SUMMARY_RE.containsMatchIn(fields.firstOrNull()?.trim().orEmpty())

private fun isBlankRecord(fields: List<String>): Boolean = fields.all { it.isBlank() }

/** 列名归一化：去 BOM/引号/各种空白，全角括号折半角 —— 「金额（元）」与「金额(元)」是一列。 */
private fun normKey(raw: String): String = raw.trim()
    .removeSurrounding("\"")
    .replace(BOM, "")
    .replace('（', '(')
    .replace('）', ')')
    .replace(" ", "")
    .replace("\t", "")
    .replace("\u3000", "")

private fun BillTable.colOf(name: String, vararg aliases: String): Int {
    val wanted = (listOf(name) + aliases).map(::normKey)
    // 先精确匹配再前缀匹配：不同年份的导出会往列名后加单位（「金额(元)」「商品说明信息」
    // 「退款金额(元)」），只精确匹配会整列落空。
    for (w in wanted) {
        val exact = header.indexOfFirst { normKey(it) == w }
        if (exact >= 0) return exact
    }
    for (w in wanted) {
        val prefix = header.indexOfFirst { normKey(it).startsWith(w) }
        if (prefix >= 0) return prefix
    }
    return -1
}

private fun at(row: List<String>, index: Int): String =
    if (index in row.indices) row[index] else ""

/** 缺列时给一句能自查的话：把真实表头打出来，看一眼就知道是列名变了还是文件不对。 */
private fun missingColumn(table: BillTable, name: String, vararg aliases: String): String? =
    if (table.colOf(name, *aliases) >= 0) {
        null
    } else {
        "表头里找不到「$name」列。实际表头：${table.header.joinToString(" | ")}"
    }

private fun noTable(keyColumn: String, formatName: String): Result<Nothing> = failed(
    "没找到以「$keyColumn」开头的${formatName}表头行。" +
        "可能是：① 那是 Excel/PDF 而不是 CSV 文本；② 平台改了导出格式；③ 来源选错了。",
)

private fun failed(message: String): Result<Nothing> =
    Result.failure(IllegalArgumentException(message))

/* ---------------- 金额与时间 ---------------- */

/** 「¥45.00」「+12.00」「-1,234.56」「45.00元」→ (绝对值文本, 显式符号 1/-1/0)。 */
private fun splitSignedYuan(raw: String): Pair<String, Int> {
    var s = raw.trim()
        .replace("¥", "").replace("￥", "").replace("元", "")
        .replace(",", "").replace("，", "")
        .replace(" ", "").replace("\t", "")
        .replace("\u00A0", "").replace("\u200B", "")
    if (s.isEmpty()) return s to 0
    var sign = 0
    when {
        s.startsWith("-") -> { sign = -1; s = s.substring(1) }
        s.startsWith("+") -> { sign = 1; s = s.substring(1) }
        s.endsWith("-") -> { sign = -1; s = s.dropLast(1) }
        s.endsWith("+") -> { sign = 1; s = s.dropLast(1) }
    }
    return s to sign
}

private fun centsOf(raw: String): Long = parseYuanToCents(splitSignedYuan(raw).first)

/** [ImportedTx.yuanText] 的口径：无符号、无货币符号、两位小数。 */
private fun yuanTextOf(cents: Long): String = String.format(Locale.CHINA, "%.2f", cents / 100.0)

/**
 * 账单里的时间戳格式。微信新版 `2024-01-31 22:11:23`，某些年份/渠道给
 * `2024年1月31日 22:11:23`；支付宝多为前者，也见过 `2024/1/5 8:00:00`。
 * 只给到分钟的按整分算；一律用系统时区解释（账单本来就是本地时间）。
 */
private val DATE_PATTERNS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy年M月d日 HH:mm:ss",
    "yyyy/M/d HH:mm:ss",
    "yyyy-M-d HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "yyyy年M月d日 HH:mm",
    "yyyy/M/d HH:mm",
    "yyyy-MM-dd",
    "yyyy年M月d日",
)

private fun parseBillTime(raw: String): Long? {
    val s = raw.trim().removeSurrounding("\"").replace(BOM, "").replace('\t', ' ').trim()
    if (s.isEmpty()) return null
    for (p in DATE_PATTERNS) {
        val f = SimpleDateFormat(p, Locale.CHINA)
        f.isLenient = false // 宽松模式会把「2024-13-45」也「算」出一个日期，那种脏值宁可不认
        val pos = ParsePosition(0)
        val date: Date? = f.parse(s, pos)
        if (date != null && pos.index == s.length) return date.time
    }
    return null
}

/** 备注 = 说明/商品/备注里非空的部分拼起来；同一个值不写两遍。 */
private fun joinParts(vararg parts: String): String =
    parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" · ")

/* ---------------- 剔除统计 ---------------- */

/**
 * 被规则剔除的行数。它存在的唯一理由：解析出 0 条时，「没有可导入的记录」
 * 这种文案等于没说 —— 用户需要知道数据是被**规则删掉**的，还是文件根本没读对。
 */
private class SkipTally {
    var neutral = 0 // 收/支 = "/"、中性交易（转账、零钱通进出）
    var refunded = 0 // 全额退款 / 行内减退款后净额归零
    var zero = 0 // 金额为 0 或金额列读不出数字
    var badTime = 0 // 时间戳解析失败
    var closed = 0 // 交易关闭（钱没动）
    var transfer = 0 // 转账：没有目标账户可恢复，宁可不记
    var unknownDir = 0 // 收/支 列取值认不出来

    fun describe(): String {
        val parts = ArrayList<String>(7)
        if (neutral > 0) parts.add("中性交易 $neutral 笔")
        if (refunded > 0) parts.add("全额退款 $refunded 笔")
        if (zero > 0) parts.add("金额为 0 $zero 笔")
        if (closed > 0) parts.add("交易关闭 $closed 笔")
        if (transfer > 0) parts.add("转账 $transfer 笔")
        if (badTime > 0) parts.add("时间读不出 $badTime 行")
        if (unknownDir > 0) parts.add("收支方向认不出 $unknownDir 笔")
        return if (parts.isEmpty()) "" else "已剔除：" + parts.joinToString("、") + "。"
    }
}

/* ---------------- 微信 ---------------- */

/**
 * 微信支付账单明细。UTF-8（可带 BOM），前约 17 行是元数据，表头以「交易时间」起头。
 *
 * 剔掉这些才叫「收支」：
 *   · 收/支 = "/"、「中性交易」：零钱通进出、提现、信用卡还款 —— 都是左口袋到右口袋，
 *     记进来支出会虚高一大截（还一笔 5000 的信用卡 = 凭空 5000 消费）。
 *   · 当前状态含「已全额退款」：原支出与退款两头都不记，净影响本来就是 0。
 *   · 金额为 0：0 元单、全额抵扣留下的空行。
 */
fun parseWechatBill(text: String): Result<List<ImportedTx>> {
    val table = readBillTable(text, "交易时间") ?: return noTable("交易时间", "微信账单")
    val problem = listOfNotNull(
        missingColumn(table, "交易类型"),
        missingColumn(table, "交易对方"),
        missingColumn(table, "金额(元)", "金额"),
        missingColumn(table, "收/支", "收支"),
    ).firstOrNull()
    if (problem != null) return failed("$problem（识别为微信账单）")

    val cTime = table.colOf("交易时间")
    val cType = table.colOf("交易类型")
    val cParty = table.colOf("交易对方")
    val cGoods = table.colOf("商品", "商品说明")
    val cInOut = table.colOf("收/支", "收支")
    val cAmount = table.colOf("金额(元)", "金额")
    val cStatus = table.colOf("当前状态", "交易状态")
    val cNote = table.colOf("备注")

    val skips = SkipTally()
    val out = ArrayList<ImportedTx>()
    for (row in table.rows) {
        if (isBlankRecord(row)) continue
        val millis = parseBillTime(at(row, cTime))
        if (millis == null) {
            skips.badTime++
            continue
        }
        val type = at(row, cType)
        val status = at(row, cStatus)
        val inout = at(row, cInOut)
        val (yuan, sign) = splitSignedYuan(at(row, cAmount))
        val cents = parseYuanToCents(yuan)

        if (status.contains("已全额退款") || status.contains("对方已退还")) {
            skips.refunded++
            continue
        }
        // 退款净额：微信把部分退款单列成一行，交易类型含「退款」、金额带正号，
        // 而收/支 列经常是 "/"。跟着 "/" 一起剔掉的话，用户会发现「退了 30 块、账上却没这笔」，
        // 所以这条判定必须排在「中性交易剔除」之前。
        val refundIncome = type.contains("退款") && sign > 0
        if (!refundIncome) {
            // 「中性交易」这个标记在不同年份的导出里落在不同列（有的在交易类型、有的在收/支），
            // 两列都查一遍，漏掉一条就会把 5000 的信用卡还款记成消费。
            if (inout == "/" || inout.isBlank() ||
                type.contains("中性") || inout.contains("中性")
            ) {
                skips.neutral++
                continue
            }
            if (!inout.contains("支出") && !inout.contains("收入")) {
                skips.unknownDir++
                continue
            }
        }
        if (cents <= 0L) {
            skips.zero++
            continue
        }
        out.add(
            ImportedTx(
                millis = millis,
                expense = !refundIncome && inout.contains("支出"),
                yuanText = yuanTextOf(cents),
                counterparty = at(row, cParty),
                categoryHint = type,
                note = joinParts(at(row, cGoods), at(row, cNote)),
            ),
        )
    }
    if (out.isEmpty()) return failed(emptyMessage("微信", skips, table.rows.size))
    return Result.success(out)
}

/* ---------------- 支付宝 ---------------- */

/**
 * 支付宝交易明细。历史上两种列结构都要吃：
 *   A. 收支两列：… 收入金额 / 支出金额 …（同一行通常只有一列有值）
 *   B. 单列金额：… 收/支 + 金额(元) …
 * 编码是 GBK/GB18030（由 [BillParser] 的自动解码链负责），表头在第 5 行上下，
 * 末尾「共 X 笔」统计行由 [readBillTable] 截断。
 */
fun parseAlipayBill(text: String): Result<List<ImportedTx>> {
    val table = readBillTable(text, "交易时间") ?: return noTable("交易时间", "支付宝账单")
    val cTime = table.colOf("交易时间")
    val cCat = table.colOf("交易分类")
    val cParty = table.colOf("交易对方")
    val cNote = table.colOf("说明", "商品说明", "商品")
    val cStatus = table.colOf("交易状态", "状态")
    val cIncome = table.colOf("收入金额", "收入")
    val cExpense = table.colOf("支出金额", "支出")
    val cRefund = table.colOf("退款金额", "退款")
    val cInOut = table.colOf("收/支", "收支")
    val cAmount = table.colOf("金额(元)", "金额")
    val twoColumn = cIncome >= 0 || cExpense >= 0

    val problem = listOfNotNull(
        missingColumn(table, "交易分类", "交易对方"),
        if (twoColumn) {
            null
        } else {
            missingColumn(table, "金额(元)", "金额") ?: missingColumn(table, "收/支", "收支")
        },
    ).firstOrNull()
    if (problem != null) return failed("$problem（识别为支付宝账单）")

    val skips = SkipTally()
    val out = ArrayList<ImportedTx>()
    for (row in table.rows) {
        if (isBlankRecord(row)) continue
        val millis = parseBillTime(at(row, cTime))
        if (millis == null) {
            skips.badTime++
            continue
        }
        val status = at(row, cStatus)
        val category = at(row, cCat)
        val note = at(row, cNote)
        val party = at(row, cParty)
        // 交易关闭 = 拍下没付款/超时关闭，资金从未流动；记进去纯属噪声
        if (status.contains("交易关闭") || status.contains("已关闭")) {
            skips.closed++
            continue
        }

        if (twoColumn) {
            // 退款净额：这版导出把退款额写在同一行的「退款金额」列（状态标「成功退款」）。
            // 不在行内做减法，一笔 100 买、退 40 的交易会被记成 100 支出，
            // 而退款未必单独成行 —— 支出从此永远虚高。减到 0 以下 = 整单退完，不记。
            var expenseCents = centsOf(at(row, cExpense))
            if (expenseCents > 0L && status.contains("退款")) {
                expenseCents -= centsOf(at(row, cRefund))
                if (expenseCents <= 0L) {
                    skips.refunded++
                    continue
                }
            }
            val incomeCents = centsOf(at(row, cIncome))
            var kept = false
            if (expenseCents > 0L) {
                out.add(alipayTx(millis, true, expenseCents, party, category, note, status))
                kept = true
            }
            if (incomeCents > 0L) {
                out.add(alipayTx(millis, false, incomeCents, party, category, note, status))
                kept = true
            }
            if (!kept) skips.zero++
            continue
        }

        // 单列金额 + 收/支
        val inout = at(row, cInOut)
        val (yuan, sign) = splitSignedYuan(at(row, cAmount))
        val cents = parseYuanToCents(yuan)
        if (status.contains("已全额退款")) {
            skips.refunded++
            continue
        }
        val refundIncome = sign > 0 &&
            (category.contains("退款") || note.contains("退款") || party.contains("退款"))
        if (!refundIncome) {
            if (inout == "/" || inout.isBlank() || category.contains("中性")) {
                skips.neutral++
                continue
            }
            if (!inout.contains("支出") && !inout.contains("收入")) {
                skips.unknownDir++
                continue
            }
        }
        if (cents <= 0L) {
            skips.zero++
            continue
        }
        out.add(alipayTx(millis, !refundIncome && inout.contains("支出"), cents, party, category, note, status))
    }
    if (out.isEmpty()) return failed(emptyMessage("支付宝", skips, table.rows.size))
    return Result.success(out)
}

private fun alipayTx(
    millis: Long,
    expense: Boolean,
    cents: Long,
    party: String,
    category: String,
    note: String,
    status: String,
): ImportedTx {
    // 状态列信息量比备注大（「待支付」「已退款」），并进备注；「交易成功」是绝大多数行的默认值，
    // 写进去只会把备注挤满没用的字。
    val statusPart = if (status.isBlank() || status == "交易成功") "" else status
    return ImportedTx(
        millis = millis,
        expense = expense,
        yuanText = yuanTextOf(cents),
        counterparty = party,
        categoryHint = category,
        note = joinParts(note, statusPart),
    )
}

/* ---------------- 简记自己的导出 ---------------- */

/**
 * 解析 [com.jianji.jizhang.ui.export.ExportScreen] 写出来的明细 CSV。
 * 用途：换机时只有导出文件、或把两份导出合并。
 *
 * 表头以实际代码为准：`日期,账户,类型,分类,金额,备注` —— **只有这 6 列**。
 * 当前导出把转账写成「类型=支出」的一行（转账的 isExpense 恒 true），转入账户根本没输出，
 * 所以这里遇到「转账」字样一律剔除，并把「简记导出不是无损格式」记进已知局限。
 */
fun parseJizhangBill(text: String): Result<List<ImportedTx>> {
    val table = readBillTable(text, "日期") ?: return noTable("日期", "简记导出")
    val problem = listOfNotNull(
        missingColumn(table, "类型"),
        missingColumn(table, "金额"),
    ).firstOrNull()
    if (problem != null) return failed("$problem（识别为简记导出）")

    val cDate = table.colOf("日期")
    val cAccount = table.colOf("账户")
    val cType = table.colOf("类型")
    val cCat = table.colOf("分类")
    val cAmount = table.colOf("金额", "金额(元)")
    val cNote = table.colOf("备注")

    val skips = SkipTally()
    val out = ArrayList<ImportedTx>()
    for (row in table.rows) {
        if (isBlankRecord(row)) continue
        val millis = parseBillTime(at(row, cDate))
        if (millis == null) {
            skips.badTime++
            continue
        }
        val type = at(row, cType)
        if (type.contains("转账")) {
            // 没有目标账户可恢复，记成支出等于凭空多一笔消费 —— 宁可不记。
            skips.transfer++
            continue
        }
        // 「方向」先判再 continue：把 continue 塞进 when 的分支里当表达式用，
        // 可读性差、而且不同 Kotlin 版本对 jump 作表达式的支持并不一致。
        val saysExpense = type.contains("支出")
        val saysIncome = type.contains("收入")
        if (!saysExpense && !saysIncome) {
            skips.unknownDir++
            continue
        }
        val expense = saysExpense
        val cents = centsOf(at(row, cAmount))
        if (cents <= 0L) {
            skips.zero++
            continue
        }
        out.add(
            ImportedTx(
                millis = millis,
                expense = expense,
                yuanText = yuanTextOf(cents),
                // 简记导出没有「交易对方」列；这个位置放账户名只为了让预览列表不是一列空白
                // （导入时账户列本来就会被忽略：目标账户由用户在下一步统一选）。
                counterparty = at(row, cAccount),
                categoryHint = at(row, cCat),
                note = at(row, cNote),
            ),
        )
    }
    if (out.isEmpty()) return failed(emptyMessage("简记导出", skips, table.rows.size))
    return Result.success(out)
}

private fun emptyMessage(formatName: String, skips: SkipTally, dataLines: Int): String =
    if (dataLines == 0) {
        "读到了${formatName}表头，但下面没有任何数据行 —— 这份账单可能是空的。"
    } else {
        "从${formatName}账单的 $dataLines 行里没找到可导入的记录。${skips.describe()}" +
            "如果这个月的账单本来就只有转账和退款，那是正常的：它们不计入收支。"
    }

/* ---------------- 汇合到落库形态 ---------------- */

/**
 * 记录 → 草稿。元→分只在这里换算一次，
 * 与手填记账共用 [parseYuanToCents]（四舍五入口径必须全 App 唯一）。
 * 分类 id 留给界面填：解析器不该看见分类表。
 */
fun List<ImportedTx>.toDrafts(): List<ImportedTxDraft> = map { row ->
    ImportedTxDraft(
        millis = row.millis,
        expense = row.expense,
        amountCents = parseYuanToCents(row.yuanText),
        categoryName = row.categoryHint,
        note = row.note,
    )
}
