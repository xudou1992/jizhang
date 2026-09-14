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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/* ---------------- 表 ---------------- */

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val orderNum: Double,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Long,
    val orderNum: Double,
)

/** 金额恒为「分」的 Long，符号由 isExpense 表达 —— 不用 Double 存钱。 */
@Entity(tableName = "transactions")
data class TxEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val isExpense: Boolean,
    val amountCents: Long,
    val categoryId: String,
    val note: String,
    val dateTime: Long,
)

data class TxWithCategory(
    @Embedded val tx: TxEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?,
)

/* ---------------- 迁移包 DTO（assets/migration.json，字段以实测为准） ---------------- */

@Serializable
data class AccountDto(
    val id: String = "",
    val name: String = "",
    val color: Int = 0,
    val orderNum: Double = 0.0,
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
)

@Serializable
data class BackupDto(
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TxDto> = emptyList(),
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

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :id")
    suspend fun txCountForAccount(id: String): Int
}

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LedgerDb : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        @Volatile
        private var instance: LedgerDb? = null

        fun get(context: Context): LedgerDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LedgerDb::class.java,
                "ledger.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

/* ---------------- 首次启动灌入旧版 608 笔 ---------------- */

object Seed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun seedIfNeeded(context: Context, db: LedgerDb) {
        val dao = db.dao()
        if (dao.txCount() > 0) return

        val raw = context.assets.open("migration.json").use { it.readBytes().decodeToString() }
        val dto = json.decodeFromString(BackupDto.serializer(), raw)

        dao.insertAccounts(
            dto.accounts.map { AccountEntity(it.id, it.name, it.color.toLong(), it.orderNum) },
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
                    amountCents = (it.amount * 100).roundToLong(),
                    categoryId = it.categoryId ?: "",
                    note = it.description ?: it.title ?: "",
                    dateTime = it.dateTime,
                )
            },
        )
    }
}
