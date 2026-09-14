package com.jianji.jizhang.data.backup

import android.content.Context
import com.jianji.jizhang.data.AccountDto
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.BackupDto
import com.jianji.jizhang.data.CategoryDto
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.LedgerDb
import com.jianji.jizhang.data.TxDto
import com.jianji.jizhang.data.TxEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * 备份的编解码：把三张表导出成 JSON，或从 JSON 写回。
 *
 * DB 金额单位是「分」(Long)，DTO 里是「元」(Double)——
 * 导出 `元 = 分 / 100.0`，导入 `分 = (元 * 100).roundToLong()`，方向必须严格一致。
 */
object BackupCodec {
    // prettyPrint 便于用户在坚果云里直接查看；encodeDefaults 让空字段也写出去，避免版本间缺字段。
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** 读出三张表，转成 BackupDto 再序列化。 */
    suspend fun export(context: Context): String {
        val ctx = context.applicationContext
        val dao = LedgerDb.get(ctx).dao()

        val accounts = dao.observeAccounts().first()
        val categories = dao.observeCategories().first()
        val txs = dao.observeAll().first().map { it.tx }

        val dto = BackupDto(
            accounts = accounts.map {
                AccountDto(id = it.id, name = it.name, color = it.color.toInt(), orderNum = it.orderNum)
            },
            categories = categories.map {
                CategoryDto(id = it.id, name = it.name, color = it.color.toInt(), orderNum = it.orderNum)
            },
            transactions = txs.map {
                TxDto(
                    id = it.id,
                    accountId = it.accountId,
                    type = if (it.isExpense) "EXPENSE" else "INCOME",
                    amount = it.amountCents / 100.0,
                    title = null,
                    // 旧版 note 字段映射到 DTO 的 description，与新版 Seed 互相兼容。
                    description = it.note,
                    dateTime = it.dateTime,
                    categoryId = it.categoryId,
                )
            },
        )
        // 显式传 serializer：reified 版 encodeToString 需要额外 import，显式版不会踩坑。
        return json.encodeToString(BackupDto.serializer(), dto)
    }

    /** 只解析不写库：给「恢复前预览」统计条数用。 */
    fun parse(jsonText: String): BackupDto =
        json.decodeFromString(BackupDto.serializer(), jsonText)

    /**
     * 解析 JSON 写回 DB。DAO 只有 REPLACE 语义的 insert，没有 delete-all，
     * 所以「恢复」是按 id 覆盖合并：已有相同 id 的记录被覆盖，新增的被插入。
     * 返回本次写入的条目总数（账户 + 分类 + 交易）。
     */
    suspend fun restore(context: Context, jsonText: String): Int {
        val ctx = context.applicationContext
        val dto = this.json.decodeFromString(BackupDto.serializer(), jsonText)
        val dao = LedgerDb.get(ctx).dao()

        val accounts = dto.accounts.map {
            AccountEntity(id = it.id, name = it.name, color = it.color.toLong(), orderNum = it.orderNum)
        }
        val categories = dto.categories.map {
            CategoryEntity(id = it.id, name = it.name, color = it.color.toLong(), orderNum = it.orderNum)
        }
        val txs = dto.transactions.map {
            TxEntity(
                id = it.id,
                accountId = it.accountId,
                isExpense = it.type.equals("EXPENSE", ignoreCase = true),
                amountCents = (it.amount * 100).roundToLong(),
                categoryId = it.categoryId ?: "",
                note = it.description ?: it.title ?: "",
                dateTime = it.dateTime,
            )
        }

        dao.insertAccounts(accounts)
        dao.insertCategories(categories)
        dao.insertTxs(txs)
        return accounts.size + categories.size + txs.size
    }
}
