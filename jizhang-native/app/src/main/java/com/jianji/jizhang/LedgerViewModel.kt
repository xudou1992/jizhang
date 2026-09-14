package com.jianji.jizhang

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.LedgerDb
import com.jianji.jizhang.data.Seed
import com.jianji.jizhang.data.TxEntity
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.backup.nutstoreSettingsFlow
import com.jianji.jizhang.data.backup.BackupOrigin
import com.jianji.jizhang.data.backup.runBackupNow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = LedgerDb.get(app)
    private val dao = db.dao()

    val all: StateFlow<List<TxWithCategory>>
    val categories: StateFlow<List<CategoryEntity>>
    val accounts: StateFlow<List<AccountEntity>>

    /** 记账后的防抖备份任务：3 分钟内连记多笔只传一次。 */
    private var autoBackupJob: Job? = null

    init {
        viewModelScope.launch { Seed.seedIfNeeded(app, db) }
        all = dao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        categories = dao.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        accounts = dao.observeAccounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** 新账单默认落到第一个账户（迁移包里就一个「默认账户」）。 */
    fun accountId(): String = accounts.value.firstOrNull()?.id ?: ""

    fun addTx(
        categoryId: String,
        isExpense: Boolean,
        amountCents: Long,
        note: String,
    ) {
        if (amountCents <= 0) return
        viewModelScope.launch {
            dao.insertTxs(
                listOf(
                    TxEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId(),
                        isExpense = isExpense,
                        amountCents = amountCents,
                        categoryId = categoryId,
                        note = note,
                        dateTime = System.currentTimeMillis(),
                    ),
                ),
            )
            scheduleAutoBackup()
        }
    }

    /**
     * 记一笔后自动同步：延迟 3 分钟再传，期间又记账就重新计时（合并成一次），
     * 避免连记几笔就连传几次。只在「自动备份」开关开启时执行；
     * App 被杀掉没传成的日子由每日 Worker 兜底。
     *
     * 这里 `archive = false`：只刷新云端「最新版」，不在 auto/ 里堆归档 ——
     * 归档由每日 Worker 一天一份，手工备份走 manual/，三条线互不覆盖。
     */
    private fun scheduleAutoBackup() {
        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val enabled = app.nutstoreSettingsFlow().first().enabled
            if (!enabled) return@launch
            delay(AUTO_BACKUP_DEBOUNCE_MS)
            runBackupNow(app, BackupOrigin.AUTO, archive = false)
        }
    }

    companion object {
        private const val AUTO_BACKUP_DEBOUNCE_MS = 3 * 60 * 1000L
    }

    /* ---------------- 编辑 / 删除 ---------------- */

    fun deleteTx(id: String) {
        viewModelScope.launch {
            dao.deleteTx(id)
            scheduleAutoBackup()
        }
    }

    /** 编辑一条已有账单。dateTime 保持原值，编辑不该改变它发生的时刻。 */
    fun updateTx(
        id: String,
        categoryId: String,
        isExpense: Boolean,
        amountCents: Long,
        note: String,
        dateTime: Long,
    ) {
        if (amountCents <= 0) return
        viewModelScope.launch {
            // 保留原账户：编辑一笔账单不该把它挪到别的账户下。
            val oldAccount = dao.txById(id)?.accountId
            dao.updateTx(
                TxEntity(
                    id = id,
                    accountId = oldAccount ?: accountId(),
                    isExpense = isExpense,
                    amountCents = amountCents,
                    categoryId = categoryId,
                    note = note,
                    dateTime = dateTime,
                ),
            )
        }
    }

    /* ---------------- 分类维护 ---------------- */

    fun addCategory(name: String, color: Long) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val nextOrder = (categories.value.maxOfOrNull { it.orderNum } ?: 0.0) + 1.0
            dao.insertCategories(
                listOf(
                    CategoryEntity(
                        id = UUID.randomUUID().toString(),
                        name = trimmed,
                        color = color,
                        orderNum = nextOrder,
                    ),
                ),
            )
        }
    }

    fun updateCategory(category: CategoryEntity) {
        viewModelScope.launch { dao.updateCategory(category) }
    }

    /** 删除分类。有账单在用时拒绝，返回 false，让 UI 给用户提示。 */
    fun deleteCategory(id: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val used = dao.txCountForCategory(id)
            if (used > 0) {
                onResult(false)
            } else {
                dao.deleteCategory(id)
                onResult(true)
            }
        }
    }

    /* ---------------- 账户维护 ---------------- */

    fun addAccount(name: String, color: Long) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val nextOrder = (accounts.value.maxOfOrNull { it.orderNum } ?: 0.0) + 1.0
            dao.insertAccounts(
                listOf(
                    AccountEntity(
                        id = UUID.randomUUID().toString(),
                        name = trimmed,
                        color = color,
                        orderNum = nextOrder,
                    ),
                ),
            )
        }
    }

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch { dao.updateAccount(account) }
    }

    fun deleteAccount(id: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val used = dao.txCountForAccount(id)
            // 一个账户都不剩时也不许删，否则新账单无处可落。
            if (used > 0 || accounts.value.size <= 1) {
                onResult(false)
            } else {
                dao.deleteAccount(id)
                onResult(true)
            }
        }
    }
}
