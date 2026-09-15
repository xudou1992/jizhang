package com.jianji.jizhang

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.BudgetEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.LedgerDb
import com.jianji.jizhang.data.Seed
import com.jianji.jizhang.data.TxEntity
import com.jianji.jizhang.data.TxDraft
import com.jianji.jizhang.data.TxWithCategory
import com.jianji.jizhang.data.balanceMap
import com.jianji.jizhang.data.centsToYuan
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
import kotlin.math.abs

class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val db = LedgerDb.get(app)
    private val dao = db.dao()

    val all: StateFlow<List<TxWithCategory>>
    val categories: StateFlow<List<CategoryEntity>>
    val accounts: StateFlow<List<AccountEntity>>
    val budgets: StateFlow<List<BudgetEntity>>

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
        budgets = dao.observeBudgets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** 新账单默认落到第一个账户（迁移包里就一个「默认账户」）。 */
    fun accountId(): String = accounts.value.firstOrNull()?.id ?: ""

    /** 协程里取账户列表的真实值。不能信 StateFlow 缓存：stateIn 重置瞬间会给出空表。 */
    private suspend fun realAccountId(fallback: String): String {
        if (fallback.isNotBlank()) return fallback
        val accountsNow = accounts.value.ifEmpty { runCatching { dao.observeAccounts().first() }.getOrNull() }
        return accountsNow?.firstOrNull()?.id ?: ""
    }

    /**
     * 新建一笔。账户为空串时回退到第一个账户；日期由表单给（默认「现在」）。
     * 一个账户都没有时**拒绝落库并提示** —— 静默写入 accountId="" 会造出
     * 任何账户统计都收不进去的孤儿账单，比保存失败严重得多。
     *
     * 转账（toAccountId 非空）额外校验：目标账户必须已选定且 ≠ 源账户 ——
     * 「A 转给 A」会让余额先减后加原地抵消，却留下一条永远对不上账的流水。
     */
    fun addTx(draft: TxDraft) {
        if (draft.amountCents <= 0) {
            // 普通收支按旧行为静默返回（UI 自己有按钮禁用）；转账的非法金额必须出声，
            // 否则用户点了「转账」什么也没发生，只会以为 App 卡了。
            if (draft.toAccountId.isNotBlank()) {
                Toast.makeText(getApplication(), "转账金额必须大于 0", Toast.LENGTH_SHORT).show()
            }
            return
        }
        viewModelScope.launch {
            val acc = realAccountId(draft.accountId)
            if (acc.isBlank()) {
                Toast.makeText(getApplication(), "还没有任何账户，请先在设置→账户管理新建", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!validateTransfer(draft, acc)) return@launch
            dao.insertTxs(listOf(toEntity(draft, UUID.randomUUID().toString(), acc)))
            scheduleAutoBackup()
        }
    }

    /** 转账合法性检查；非法时 Toast 并返回 false（普通收支直接 true）。 */
    private fun validateTransfer(draft: TxDraft, resolvedAccount: String): Boolean {
        if (draft.toAccountId.isBlank()) return true
        if (draft.toAccountId == resolvedAccount) {
            Toast.makeText(getApplication(), "转出的账户和转入的账户不能相同", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /**
     * draft + 已解析的源账户 → 实体。addTx / updateTx 共用一份，
     * 转账行「恒 isExpense=true、categoryId 恒空」的不变式也只在这里落实一次，
     * 免得两条写入路径各写各的、其中一条漏了规范化。
     */
    private fun toEntity(draft: TxDraft, id: String, account: String): TxEntity {
        val transfer = draft.toAccountId.isNotBlank()
        return TxEntity(
            id = id,
            accountId = account,
            isExpense = if (transfer) true else draft.isExpense,
            amountCents = draft.amountCents,
            categoryId = if (transfer) "" else draft.categoryId,
            note = draft.note,
            dateTime = draft.dateTime,
            toAccountId = draft.toAccountId,
        )
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
            // 开关要等到防抖结束再读一次：否则用户在 3 分钟窗口内关掉自动备份，
            // 这次上传照样会发出去（读得太早了）。
            delay(AUTO_BACKUP_DEBOUNCE_MS)
            if (!app.nutstoreSettingsFlow().first().enabled) return@launch
            runBackupNow(app, BackupOrigin.AUTO, archive = false)
        }
    }

    companion object {
        private const val AUTO_BACKUP_DEBOUNCE_MS = 3 * 60 * 1000L
    }

    /* ---------------- 编辑 / 删除 ---------------- */

    /**
     * 删除一笔。**先查出整行**再删，并通过 [onDeleted] 把被删的记录交回调用方 ——
     * UI 拿它挂 Snackbar「撤销」，误删不再只能靠云端备份救。查不到行（已被删过）
     * 则什么都不做，回调也不会触发。
     */
    fun deleteTx(id: String, onDeleted: (TxEntity) -> Unit = {}) {
        viewModelScope.launch {
            val old = dao.txById(id) ?: return@launch
            dao.deleteTx(id)
            onDeleted(old)
            scheduleAutoBackup()
        }
    }

    /** 撤销删除：把刚才那笔原样写回（id 不变，账户余额/统计自动归位）。 */
    fun undoDeleteTx(entity: TxEntity) {
        viewModelScope.launch {
            dao.insertTxs(listOf(entity))
            scheduleAutoBackup()
        }
    }

    /**
     * 批量删除（账单页多选）。与单条删除同规则：先查回整行再删，
     * 整包交回 [onDeleted] 给 UI 挂一条「撤销」——不是逐条弹 Snackbar。
     */
    fun deleteTxMany(ids: List<String>, onDeleted: (List<TxEntity>) -> Unit = {}) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val olds = ids.mapNotNull { dao.txById(it) }
            if (olds.isEmpty()) return@launch
            olds.forEach { dao.deleteTx(it.id) }
            onDeleted(olds)
            scheduleAutoBackup()
        }
    }

    /** 撤销批量删除：整包写回。 */
    fun undoDeleteMany(entities: List<TxEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch {
            dao.insertTxs(entities)
            scheduleAutoBackup()
        }
    }

    /**
     * 账单导入落库（微信/支付宝/简记 CSV）。调用方（MainActivity）已把
     * ImportedTxDraft 映射成 TxDraft（分类名→id 匹配过）。一次插入、一次备份调度，
     * 不走逐条 addTx 的 N 次校验；金额≤0 的行防御性剔掉（ImportScreen 已挡一道）。
     */
    fun importTxs(drafts: List<TxDraft>) {
        if (drafts.isEmpty()) return
        viewModelScope.launch {
            val fallback = realAccountId("")
            val entities = drafts
                .filter { it.amountCents > 0 }
                .map { toEntity(it, UUID.randomUUID().toString(), it.accountId.ifBlank { fallback }) }
            if (entities.isEmpty()) {
                Toast.makeText(getApplication(), "没有可导入的记录", Toast.LENGTH_SHORT).show()
                return@launch
            }
            dao.insertTxs(entities)
            Toast.makeText(getApplication(), "已导入 ${entities.size} 笔", Toast.LENGTH_SHORT).show()
            scheduleAutoBackup()
        }
    }

    /**
     * 编辑一条已有账单。
     *
     * 账户的兜底顺序：表单明确选了就用表单的，否则沿用原账户（编辑不该把账单
     * 悄悄挪到别的账户下），最后才回退到第一个账户。
     */
    fun updateTx(id: String, draft: TxDraft) {
        if (draft.amountCents <= 0) return
        viewModelScope.launch {
            val oldAccount = dao.txById(id)?.accountId ?: ""
            val acc = realAccountId(draft.accountId.ifBlank { oldAccount })
            if (acc.isBlank()) {
                Toast.makeText(getApplication(), "还没有任何账户，无法保存", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!validateTransfer(draft, acc)) return@launch
            dao.updateTx(toEntity(draft, id, acc))
            // 编辑同样要触发同步：addTx / deleteTx 都有，这里原来漏了。
            scheduleAutoBackup()
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
                // 分类没了，挂在它下面的预算也一起删：留着会画出指向空气的进度条。
                dao.deleteBudgetFor(id)
                onResult(true)
            }
        }
    }

    /* ---------------- 预算 ---------------- */

    /**
     * 新建/更新一条预算；[amountCents] <= 0 视为「清空预算」直接删除 ——
     * 0 额预算和没有预算在展示上无法区分，留着只会让「已用 120% / 额度 0」这种
     * 除零式进度条流出来。id 由 UI 传入（新建时给新 UUID，编辑时传原 id）。
     */
    fun setBudget(id: String, targetId: String, amountCents: Long, carryOver: Boolean, startMonth: String) {
        viewModelScope.launch {
            if (amountCents <= 0L) {
                dao.deleteBudget(id)
            } else {
                dao.insertBudget(
                    BudgetEntity(
                        id = id,
                        targetId = targetId,
                        amountCents = amountCents,
                        carryOver = carryOver,
                        startMonth = startMonth,
                    ),
                )
            }
        }
    }

    fun removeBudget(id: String) {
        viewModelScope.launch { dao.deleteBudget(id) }
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

    /** 改账户初始余额。走 copy 而不是整对象覆盖：调用方只该有权改这一个字段。 */
    fun setAccountInitial(account: AccountEntity, initialCents: Long) {
        viewModelScope.launch { dao.updateAccount(account.copy(initialCents = initialCents)) }
    }

    /**
     * 把账户余额「调整到」[targetCents]（对账场景：用户核对了银行卡真实余额）。
     *
     * 实现方式是补一笔「余额调整」流水（差额>0 记收入、<0 记支出，categoryId 空串），
     * 而不是直接改 initialCents —— 后者会把差异抹得无声无息，流水合计与余额从此
     * 永远对不上；补流水则调整记录本身可查、可删、可进备份。
     *
     * 余额一律用 balanceMap 现算：不信任 StateFlow 缓存（stateIn 重置瞬间是空表，
     * 拿空表算出的「当前余额 = 初始余额」会把调整额算错）。
     */
    fun adjustBalanceTo(accountId: String, targetCents: Long) {
        viewModelScope.launch {
            val accountList = dao.observeAccounts().first()
            if (accountList.none { it.id == accountId }) return@launch
            val current = balanceMap(dao.observeAll().first(), accountList)[accountId] ?: 0L
            val delta = targetCents - current
            if (delta == 0L) {
                Toast.makeText(getApplication(), "当前余额已是该值，无需调整", Toast.LENGTH_SHORT).show()
                return@launch
            }
            dao.insertTxs(
                listOf(
                    TxEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId,
                        isExpense = delta < 0,
                        amountCents = abs(delta),
                        categoryId = "",
                        note = "余额调整",
                        dateTime = System.currentTimeMillis(),
                    ),
                ),
            )
            val dir = if (delta > 0) "增加" else "减少"
            Toast.makeText(getApplication(), "已$dir ${centsToYuan(abs(delta))} 元", Toast.LENGTH_SHORT).show()
            scheduleAutoBackup()
        }
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
