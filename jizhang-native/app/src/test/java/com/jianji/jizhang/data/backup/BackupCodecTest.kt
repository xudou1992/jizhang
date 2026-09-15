package com.jianji.jizhang.data.backup

import com.jianji.jizhang.data.AccountEntity
import com.jianji.jizhang.data.BudgetDto
import com.jianji.jizhang.data.BudgetEntity
import com.jianji.jizhang.data.CategoryEntity
import com.jianji.jizhang.data.TxDto
import com.jianji.jizhang.data.TxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份边界（实体 ↔ DTO）的回归测试。
 *
 * 这是数据唯一「离开库、再回到库」的通道，有两条约定在这里各过一次：
 *  1. 金额：库里是「分」(Long)，DTO 里是「元」(Double)
 *  2. 颜色：库里是符号扩展的 32 位 ARGB（不透明色为负数），DTO 里是 Int
 *
 * 两边都必须**原样**往返。出错的后果是「恢复完备份发现钱少了 / 颜色变了」，
 * 而且不会报任何错 —— 所以只能靠测试拦。
 */
class BackupCodecTest {

    private fun tx(
        cents: Long,
        expense: Boolean = true,
        note: String = "",
    ) = TxEntity(
        id = "t1",
        accountId = "a1",
        isExpense = expense,
        amountCents = cents,
        categoryId = "c1",
        note = note,
        dateTime = 1_700_000_000_000L,
    )

    @Test
    fun 金额走一遍备份边界仍然原样() {
        // 穷举 0.01~99.99。把 `amount = amountCents / 100.0` 写成 `/ 100`（整除），
        // 或者 restore 侧绕过 yuanToCents 自己写 toLong() 截断，这里立刻红。
        for (cents in 1L..9999L) {
            val back = tx(cents).toDto().toEntity()
            assertEquals("$cents 分经备份往返后变了", cents, back.amountCents)
        }
    }

    @Test
    fun 大额金额往返也不丢分() {
        listOf(1L, 7L, 29L, 115L, 123_456L, 99_999_999L).forEach { cents ->
            assertEquals(cents, tx(cents).toDto().toEntity().amountCents)
        }
    }

    @Test
    fun 颜色走一遍备份边界位模式不变() {
        listOf(
            0L,
            0xFFE5484DL.toInt().toLong(), // 不透明红：Long 是负数
            0x80FF0000L.toInt().toLong(), // 半透明：alpha 必须保住
            0xFF6E56CFL.toInt().toLong(),
        ).forEach { stored ->
            assertEquals(
                stored,
                AccountEntity("a1", "现金", stored, 1.0).toDto().toEntity().color,
            )
            assertEquals(
                stored,
                CategoryEntity("c1", "餐饮", stored, 1.0).toDto().toEntity().color,
            )
        }
    }

    @Test
    fun 收支方向往返不变() {
        assertTrue(tx(100, expense = true).toDto().toEntity().isExpense)
        assertFalse(tx(100, expense = false).toDto().toEntity().isExpense)
    }

    @Test
    fun 类型字符串大小写不敏感且只认EXPENSE() {
        assertTrue(TxDto(type = "expense").toEntity().isExpense)
        assertTrue(TxDto(type = "EXPENSE").toEntity().isExpense)
        assertFalse(TxDto(type = "income").toEntity().isExpense)
        // 认不出的一律当收入 —— 与原实现一致：只有 EXPENSE 才算支出
        assertFalse(TxDto(type = "垃圾").toEntity().isExpense)
    }

    @Test
    fun 备注字段兼容新旧两种写法() {
        // 新版：实体 note ↔ DTO description
        assertEquals("午饭", tx(100, note = "午饭").toDto().toEntity().note)
        // 旧版备份只有 title，恢复时要接得住
        assertEquals("旧备注", TxDto(description = null, title = "旧备注").toEntity().note)
        // description 优先于 title
        assertEquals("新", TxDto(description = "新", title = "旧").toEntity().note)
        // 都没有就是空串，不能是 null
        assertEquals("", TxDto().toEntity().note)
    }

    @Test
    fun 空分类ID归一成空串() {
        // categoryId 在 DTO 里可空、在实体里不可空，别让 null 漏进库
        assertEquals("", TxDto(categoryId = null).toEntity().categoryId)
        assertEquals("c9", TxDto(categoryId = "c9").toEntity().categoryId)
    }

    @Test
    fun 转账目标账户走一遍备份边界仍然原样() {
        // 转账行靠 toAccountId 非空来识别：备份往返后必须还在，
        // 否则恢复回来「转账」会退化成普通支出，把支出统计灌水。
        val transfer = tx(88_00L).copy(toAccountId = "a2", categoryId = "")
        val back = transfer.toDto().toEntity()
        assertEquals("a2", back.toAccountId)
        assertEquals("", back.categoryId) // 转账不带分类，也要能穿过去
        assertTrue(back.isExpense)
        // 普通收支的 toAccountId 是空串，往返后不能被写成别的
        assertEquals("", tx(100).toDto().toEntity().toAccountId)
        // 旧备份没有该字段 → kotlinx 用默认值空串，按普通收支解析
        assertEquals("", TxDto().toAccountId)
    }

    @Test
    fun BudgetDto往返字段不丢() {
        // 预算金额 DTO 与实体同为「分」：这里锁住「不要有人在某一边偷偷换算成元」，
        // 那会让恢复后所有预算额度差 100 倍。
        val budget = BudgetEntity(
            id = "b1",
            targetId = "c1",
            amountCents = 1_500_00L,
            carryOver = true,
            startMonth = "2026-09",
        )
        assertEquals(budget, budget.toDto().toEntity())
        // 总预算（targetId 空串）语义也要原样往返
        val total = BudgetEntity("b2", "", 3_000_00L, false, "2026-01")
        assertEquals("", total.toDto().toEntity().targetId)
        assertEquals(total, total.toDto().toEntity())
    }
}
