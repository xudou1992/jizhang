package com.jianji.jizhang.data.backup

import android.content.Context
import com.jianji.jizhang.data.AccountDto
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.BackupDto
import com.jianji.jizhang.data.BudgetDto
import com.jianji.jizhang.data.BudgetEntity
import com.jianji.jizhang.data.CategoryDto
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.LedgerDb
import com.jianji.jizhang.data.TxDto
import com.jianji.jizhang.data.TxEntity
import com.jianji.jizhang.data.yuanToCents
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 备份的编解码：把三张表导出成 JSON，或从 JSON 写回。
 *
 * DB 金额单位是「分」(Long)，DTO 里是「元」(Double)——
 * 导出 `元 = 分 / 100.0`，导入 `分 = yuanToCents(元)`（四舍五入），方向必须严格一致。
 */
object BackupCodec {
    /** 当前备份文件格式版本，与 [BackupDto.schemaVersion] 对应。v2 = 转账 + 初始余额 + 预算。 */
    const val SCHEMA_VERSION = 2

    // prettyPrint 便于用户在坚果云里直接查看；encodeDefaults 让空字段也写出去，避免版本间缺字段；
    // ignoreUnknownKeys 让旧版 App 能读未来版本多出来的字段（而不是整份备份直接解析失败）。
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 读出四张表，转成 BackupDto 再序列化。 */
    suspend fun export(context: Context): String {
        val ctx = context.applicationContext
        val db = LedgerDb.get(ctx)
        val dao = db.dao()

        // 三张表必须在**同一个事务**里读：否则导出途中记了一笔，
        // 交易列表带了新账、账户汇总却没带 —— 备份自相矛盾。
        val dto = db.withTransaction {
            BackupDto(
                schemaVersion = SCHEMA_VERSION,
                accounts = dao.observeAccounts().first().map { it.toDto() },
                categories = dao.observeCategories().first().map { it.toDto() },
                transactions = dao.observeAll().first().map { it.tx.toDto() },
                budgets = dao.observeBudgets().first().map { it.toDto() },
            )
        }
        // 显式传 serializer：reified 版 encodeToString 需要额外 import，显式版不会踩坑。
        return json.encodeToString(BackupDto.serializer(), dto)
    }

    /** 只解析不写库：给「恢复前预览」统计条数用。 */
    fun parse(jsonText: String): BackupDto =
        json.decodeFromString(BackupDto.serializer(), jsonText)

    /**
     * 解析 JSON 写回 DB。DAO 只有 REPLACE 语义的 insert，没有 delete-all，
     * 所以「恢复」是按 id 覆盖合并：已有相同 id 的记录被覆盖，新增的被插入。
     *
     * 三道防线：
     * 1. 拒绝 schemaVersion 高于本 App 的备份（未来格式缺字段会静默丢数据）；
     * 2. amountCents <= 0 的脏数据直接过滤，不进库（符号由 isExpense 表达，非正数只会把统计搅坏）；
     * 3. 四张表写在**同一个事务**里：JSON 中途出错要么全写要么全不写，
     *    绝不留「账户是新的、交易是旧的」的半恢复态。
     *
     * 返回本次写入的条目数（账户 + 分类 + 有效交易）。
     */
    suspend fun restore(context: Context, jsonText: String): Int {
        val ctx = context.applicationContext
        val dto = this.json.decodeFromString(BackupDto.serializer(), jsonText)
        require(dto.schemaVersion <= SCHEMA_VERSION) {
            "这份备份来自更新版本的简记（格式 v${dto.schemaVersion} > 本 App v$SCHEMA_VERSION），无法恢复"
        }
        val db = LedgerDb.get(ctx)
        val dao = db.dao()

        val accounts = dto.accounts.map { it.toEntity() }
        val categories = dto.categories.map { it.toEntity() }
        // 转账行的 categoryId 恒为空串，这里只按金额过滤，不能顺手把空分类当脏数据丢掉。
        val txs = dto.transactions.map { it.toEntity() }.filter { it.amountCents > 0L }
        val skipped = dto.transactions.size - txs.size
        if (skipped > 0) {
            android.util.Log.w("BackupCodec", "restore: dropped $skipped invalid txs (amount <= 0)")
        }
        // v1 备份没有 budgets 字段 → 空表，恢复后只是「没有预算」，不影响其余数据。
        val budgets = dto.budgets.map { it.toEntity() }

        db.withTransaction {
            dao.insertAccounts(accounts)
            dao.insertCategories(categories)
            dao.insertTxs(txs)
            budgets.forEach { dao.insertBudget(it) }
        }
        return accounts.size + categories.size + txs.size + budgets.size
    }
}

/* ---------------- 纯映射：抽出来是为了能在 JVM 单测里直接锁住约定 ---------------- */

/**
 * 实体 ↔ DTO 的映射刻意做成**纯函数**。
 *
 * 备份是数据唯一「离开库、再回到库」的通道：金额的元/分口径、颜色的 Int 表示、
 * 备注字段的版本兼容，全都要在这条边界上过一次。原来这些映射藏在
 * `export()` / `restore()` 里（要 Context、要 Room），单测够不着 ——
 * 于是「`/ 100.0` 被写成 `/ 100`」这类静默丢小数的改动没有任何东西拦得住。
 */

fun AccountEntity.toDto(): AccountDto = AccountDto(
    id = id,
    name = name,
    color = color.toInt(),
    orderNum = orderNum,
    // 分 → 元：与 amount 同理，必须 / 100.0，整除会把 0.05 元的初始余额抹成 0。
    initialCents = initialCents / 100.0,
)

fun CategoryEntity.toDto(): CategoryDto =
    CategoryDto(id = id, name = name, color = color.toInt(), orderNum = orderNum)

fun TxEntity.toDto(): TxDto = TxDto(
    id = id,
    accountId = accountId,
    type = if (isExpense) "EXPENSE" else "INCOME",
    // 分 → 元：必须除以 100.0（浮点）。写成 `/ 100` 是整除，会把 1.15 元存成 1 元。
    amount = amountCents / 100.0,
    title = null,
    // 旧版 note 字段映射到 DTO 的 description，与新版 Seed 互相兼容。
    description = note,
    dateTime = dateTime,
    categoryId = categoryId,
    toAccountId = toAccountId,
)

fun BudgetEntity.toDto(): BudgetDto = BudgetDto(
    id = id,
    targetId = targetId,
    amountCents = amountCents,
    carryOver = carryOver,
    startMonth = startMonth,
)

fun AccountDto.toEntity(): AccountEntity = AccountEntity(
    id = id,
    name = name,
    color = color.toLong(),
    orderNum = orderNum,
    // 元 → 分：与 amount 走同一个唯一通道 yuanToCents（四舍五入，不能截断）。
    initialCents = yuanToCents(initialCents),
)

fun CategoryDto.toEntity(): CategoryEntity =
    CategoryEntity(id = id, name = name, color = color.toLong(), orderNum = orderNum)

fun TxDto.toEntity(): TxEntity = TxEntity(
    id = id,
    accountId = accountId,
    isExpense = type.equals("EXPENSE", ignoreCase = true),
    // 元 → 分：走 Money.kt 的唯一通道（四舍五入，不能截断）。
    amountCents = yuanToCents(amount),
    categoryId = categoryId ?: "",
    // 新版写 description、旧版只有 title，两者都要兼容。
    note = description ?: title ?: "",
    dateTime = dateTime,
    toAccountId = toAccountId,
)

fun BudgetDto.toEntity(): BudgetEntity = BudgetEntity(
    id = id,
    targetId = targetId,
    amountCents = amountCents,
    carryOver = carryOver,
    startMonth = startMonth,
)
