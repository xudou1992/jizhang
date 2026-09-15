package com.jianji.jizhang.data

/**
 * 记账 / 编辑表单的产物。
 *
 * 引入它之前，「记一笔」的回调是六个位置参数
 * `(categoryId, isExpense, amountCents, note)`，详情页是五个 —— 参数一多，
 * 调用方很容易把 `isExpense` 和 `amountCents` 写反，而且每加一个字段
 * （日期、账户）都要改三处签名。改成一个具名字段的对象后，加字段只动这里。
 *
 * `id`、`accountId` 的兜底都不在这里：id 由 ViewModel 生成，accountId 为空串时
 * 由 ViewModel 回退到「第一个账户 / 原账户」。
 */
data class TxDraft(
    val categoryId: String,
    val accountId: String,
    val isExpense: Boolean,
    val amountCents: Long,
    val note: String,
    val dateTime: Long,
    /** 非空串 = 记一笔转账（源账户 accountId → 目标 toAccountId）。默认空串，老调用点不用改。 */
    val toAccountId: String = "",
)
