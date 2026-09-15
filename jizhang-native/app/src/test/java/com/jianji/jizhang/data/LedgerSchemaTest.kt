package com.jianji.jizhang.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守住「Room schema 有留档」这条底线。
 *
 * `exportSchema = true` 的意义是：将来给实体加字段时，手上有上一版的 schema 基线，
 * 才能写真正的 `Migration`。一旦有人把它关回去（或者删掉 `schemas/` 目录），
 * 下一次改 schema 就只剩 `fallbackToDestructiveMigration()` 这条路 —— 静默清空整库。
 *
 * 这个测试在 JVM 上跑，schema 目录由 `build.gradle.kts` 的
 * `unitTests.all { systemProperty("jizhang.schemaDir", ...) }` 传进来。
 */
class LedgerSchemaTest {

    private val schemaRoot: File by lazy {
        val dir = System.getProperty("jizhang.schemaDir")
            ?: error("缺少系统属性 jizhang.schemaDir，检查 build.gradle.kts 的 testOptions")
        File(dir)
    }

    private val dbSchemaDir: File by lazy { File(schemaRoot, "com.jianji.jizhang.data.LedgerDb") }

    @Test
    fun schema已导出() {
        assertTrue(
            "schema 目录不存在：$dbSchemaDir（exportSchema 被关掉了？）",
            dbSchemaDir.isDirectory,
        )
        val jsons = dbSchemaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertTrue("没有导出任何版本的 schema", jsons.isNotEmpty())
    }

    @Test
    fun schema里有四张表与关键列() {
        val latest = dbSchemaDir.listFiles { f -> f.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .last()
            .readText()

        // v2：transactions 之外还有 budgets（转账/初始余额/预算的数据地基）。
        listOf("accounts", "categories", "transactions", "budgets").forEach { table ->
            assertTrue("schema 里缺少表 $table", latest.contains("\"$table\""))
        }
        listOf(
            "amountCents", "isExpense", "categoryId", "accountId", "dateTime", "orderNum",
            // v2 新增列：缺一个就说明实体改动没被导出（比如忘了升 version 或没重新构建）。
            "toAccountId", "initialCents", "targetId", "carryOver", "startMonth",
        ).forEach { column ->
            assertTrue("schema 里缺少列 $column", latest.contains("\"$column\""))
        }
    }
}
