package com.jianji.jizhang.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/* ---------------- 表 ---------------- */

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val orderNum: Double,
    /**
     * 初始余额（分）。没有它，「当前余额 = 初始 + 收支流水」这条账只能从第一笔
     * 流水记起 —— 用户开户前已有的存款无处安放，余额永远对不上真实钱包。
     */
    val initialCents: Long = 0,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val orderNum: Double,
)

/**
 * 金额恒为「分」的 Long，符号由 isExpense 表达 —— 不用 Double 存钱。
 *
 * 转账不落两行「支出+收入」：那样会把收支统计灌水（转个账等于凭空记一笔消费）。
 * 一行搞定：资金从 [accountId] 流向 [toAccountId]，[isExpense] 恒 true、
 * [categoryId] 恒空串（转账不是消费，不该占用任何分类）。
 */
@Entity(tableName = "transactions")
data class TxEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val isExpense: Boolean,
    val amountCents: Long,
    val categoryId: String,
    val note: String,
    val dateTime: Long,
    /** 非空串 = 转账记录，值 = 资金流入的目标账户 id。 */
    val toAccountId: String = "",
)

data class TxWithCategory(
    @Embedded val tx: TxEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?,
)

/** 是否转账行。判断口径只留这一处，别让 `toAccountId.isNotBlank()` 散落全项目。 */
val TxEntity.isTransfer: Boolean get() = toAccountId.isNotBlank()

/** 剔除转账行：收支统计、分类 Top、预算消耗都不该把「左口袋进右口袋」算进去。 */
fun List<TxWithCategory>.nonTransfers(): List<TxWithCategory> =
    filterNot { it.tx.isTransfer }

/**
 * 每个账户的真实余额 = 初始余额 + 非转账收入 − 非转账支出 − 转账出 + 转账入。
 *
 * 放顶层（而非 ViewModel）是因为余额口径必须只有一份实现：
 * 首页卡片、账户详情、[adjustBalanceTo] 的差额计算若各写各的，
 * 「调整到 X 元」之后界面显示却仍是旧算法，用户会以为调整没生效。
 */
fun balanceMap(txs: List<TxWithCategory>, accounts: List<AccountEntity>): Map<String, Long> {
    val map = accounts.associateTo(mutableMapOf()) { it.id to it.initialCents }
    for (row in txs) {
        val tx = row.tx
        if (tx.isTransfer) {
            // 转出账户扣钱、转入账户加钱；引用了已删账户的孤儿行不建行键（与旧行为一致，不凭空造账户）。
            map[tx.accountId]?.let { map[tx.accountId] = it - tx.amountCents }
            map[tx.toAccountId]?.let { map[tx.toAccountId] = it + tx.amountCents }
        } else {
            val delta = if (tx.isExpense) -tx.amountCents else tx.amountCents
            map[tx.accountId]?.let { map[tx.accountId] = it + delta }
        }
    }
    return map
}

/**
 * 预算。[targetId] 空串 = 总预算，否则为分类 id。
 * [startMonth]（"yyyy-MM"）之后各月逐月生效，不做「每月复制一行」——
 * 那样改一条预算要同步 N 行，改漏一月就出现新旧额度并存的矛盾数据。
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val id: String,
    /** "" = 总预算；否则 categoryId。 */
    val targetId: String,
    val amountCents: Long,
    val carryOver: Boolean = false,
    /** "yyyy-MM"，从该月起生效。 */
    val startMonth: String,
)

/* ---------------- 迁移包 DTO（assets/migration.json，字段以实测为准） ---------------- */

@Serializable
data class AccountDto(
    val id: String = "",
    val name: String = "",
    val color: Int = 0,
    val orderNum: Double = 0.0,
    /** 初始余额，单位「元」（与 TxDto.amount 同口径：DTO 层用元，库里用分）。 */
    val initialCents: Double = 0.0,
)

@Serializable
data class CategoryDto(
    val id: String = "",
    val name: String = "",
    val color: Int = 0,
    val orderNum: Double = 0.0,
)

@Serializable
data class TxDto(
    val id: String = "",
    val accountId: String = "",
    val type: String = "EXPENSE",
    val amount: Double = 0.0,
    val title: String? = null,
    val description: String? = null,
    val dateTime: Long = 0,
    val categoryId: String? = null,
    /** 非空串 = 转账目标账户。旧备份缺这个字段 → 默认空串，全部按普通收支解析。 */
    val toAccountId: String = "",
)

/** 预算的备份载体。金额直接用「分」：它是纯配置数据，不像交易要给人看/手填。 */
@Serializable
data class BudgetDto(
    val id: String = "",
    val targetId: String = "",
    val amountCents: Long = 0,
    val carryOver: Boolean = false,
    val startMonth: String = "",
)

/**
 * 备份文件格式。[schemaVersion] 从 1 开始：
 * 将来加不兼容字段就 +1，恢复端据此拒绝"来自未来版本"的备份，
 * 而不是恢复出一库残缺数据。
 *
 * v2：新增转账（toAccountId）、账户初始余额（initialCents）、预算表。
 * 三处都是「缺字段 → 默认值」的向后兼容扩展，v1 备份在 v2 App 上照样能恢复。
 */
@Serializable
data class BackupDto(
    val schemaVersion: Int = 2,
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TxDto> = emptyList(),
    val budgets: List<BudgetDto> = emptyList(),
)

/* ---------------- DAO ---------------- */

@Dao
interface LedgerDao {
    @Transaction
    @Query("SELECT * FROM transactions ORDER BY dateTime DESC")
    fun observeAll(): Flow<List<TxWithCategory>>

    @Query("SELECT * FROM categories ORDER BY orderNum ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM accounts ORDER BY orderNum ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM budgets")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTxs(items: List<TxEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(items: List<AccountEntity>)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun txCount(): Int

    /* ---------------- 编辑 / 删除 ---------------- */

    @Update
    suspend fun updateTx(tx: TxEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTx(id: String)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun txById(id: String): TxEntity?

    /* ---------------- 分类维护 ---------------- */

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    /** 删分类前先看有没有账单引用它，有就拦下来，避免留下孤儿记录。 */
    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :id")
    suspend fun txCountForCategory(id: String): Int

    /* ---------------- 账户维护 ---------------- */

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteAccount(id: String)

    /**
     * 「有账单拒删」的统计。转账行必须**两个方向都算**：
     * 只查 accountId 的话，作为转入方的账户删掉后，转账行的 toAccountId
     * 就成了悬空引用，余额里凭空少一笔入账且无从追溯。
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :id OR toAccountId = :id")
    suspend fun txCountForAccount(id: String): Int

    /* ---------------- 预算维护 ---------------- */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteBudget(id: String)

    /** 删分类时连带删掉它的预算：预算指向已消失的分类只会画出幽灵进度条。 */
    @Query("DELETE FROM budgets WHERE targetId = :targetId")
    suspend fun deleteBudgetFor(targetId: String)
}

/**
 * 数据库。
 *
 * [exportSchema] = true：每次构建把 schema 落到 `app/schemas/<类名>/<版本>.json` 并提交进仓库。
 * 这是写 Migration 的前提 —— 没有上一版的基线，加字段时只能靠
 * `fallbackToDestructiveMigration()` 静默清库（608 笔种子 + 用户之后记的全部）。
 *
 * ⚠️ 加字段/改类型的标准动作：
 *   1. 改实体 → 把 `version` 加 1 → 构建一次，让新 schema 落到 `schemas/`
 *   2. 在下面 `MIGRATIONS` 里写一条 `Migration(旧, 新)`
 *   3. `.addMigrations(*MIGRATIONS)` 挂上
 *
 * 这里**刻意不开** `fallbackToDestructiveMigration()`：忘了写迁移时 Room 会直接
 * 抛异常、App 起不来 —— 崩溃可以修，静默清空 608 笔种子 + 用户后记的账修不回来。
 * 更危险的是崩溃前自动备份还会把空库推上云、覆盖掉云端唯一的好备份（双重丢数据）。
 * 宁可开不了机，也绝不悄悄丢数据。
 */
@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TxEntity::class, BudgetEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class LedgerDb : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        /**
         * v1 → v2：转账（toAccountId）、账户初始余额（initialCents）、预算表。
         *
         * 新列全部 `NOT NULL DEFAULT`：旧行改完表结构后自动获得安全默认值
         * （空串 = 非转账、0 = 无初始余额），无需回填逻辑。
         * budgets 的列序、默认值必须与 Room 由 [BudgetEntity] 生成的 v2 schema
         * 完全一致，否则下次 Room 校验 identityHash 时拒绝打开。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN toAccountId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE accounts ADD COLUMN initialCents INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE budgets(" +
                        "id TEXT NOT NULL PRIMARY KEY," +
                        "targetId TEXT NOT NULL," +
                        "amountCents INTEGER NOT NULL," +
                        "carryOver INTEGER NOT NULL DEFAULT 0," +
                        "startMonth TEXT NOT NULL)",
                )
            }
        }

        private val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        @Volatile
        private var instance: LedgerDb? = null

        fun get(context: Context): LedgerDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerDb::class.java,
                "ledger.db",
            ).addMigrations(*MIGRATIONS)
                .build()
                .also { instance = it }
        }
    }
}

/* ---------------- 首次启动灌入旧版 608 笔 ---------------- */

object Seed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 只在**第一次**启动时，把 assets/migration.json 里的 608 笔旧账灌进来。
     *
     * 判据是「灌过没有」（[isSeeded] 这个只写一次的标记），而不是「库里有没有账单」。
     * 用后者会出事：用户把账单全删光（或恢复到一份空备份）之后重启，`txCount() == 0`
     * 会再次成立，608 笔旧账会自己长回来 —— 用户明确删掉的数据不该复活。
     *
     * 从老版本升上来的机器已经有数据、但没有标记：这里补一次标记后直接返回，不会重复灌。
     */
    suspend fun seedIfNeeded(context: Context, db: LedgerDb) {
        val app = context.applicationContext
        if (app.isSeeded()) return

        val dao = db.dao()
        if (dao.txCount() > 0) {
            // 库里已有数据（老版本升上来，标记是这次才加的）：只补标记，不灌。
            app.markSeeded()
            return
        }

        val raw = app.assets.open("migration.json").use { it.readBytes().decodeToString() }
        val dto = json.decodeFromString(BackupDto.serializer(), raw)

        dao.insertAccounts(
            dto.accounts.map {
                AccountEntity(
                    id = it.id,
                    name = it.name,
                    color = it.color.toLong(),
                    orderNum = it.orderNum,
                    // 旧 migration.json 没有这些新字段，kotlinx 缺字段时用 DTO 默认值 →
                    // 0 元初始余额、无转账，老种子数据原样进库，无需版本分支。
                    initialCents = yuanToCents(it.initialCents),
                )
            },
        )
        dao.insertCategories(
            dto.categories.map { CategoryEntity(it.id, it.name, it.color.toLong(), it.orderNum) },
        )
        dao.insertTxs(
            dto.transactions.map {
                TxEntity(
                    id = it.id,
                    accountId = it.accountId,
                    isExpense = it.type.equals("EXPENSE", ignoreCase = true),
                    amountCents = yuanToCents(it.amount),
                    categoryId = it.categoryId ?: "",
                    note = it.description ?: it.title ?: "",
                    dateTime = it.dateTime,
                    toAccountId = it.toAccountId,
                )
            },
        )

        // 三张表都写成功了才落标记；上面任何一步抛异常都不落，下次启动重试。
        app.markSeeded()
    }
}
