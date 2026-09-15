package com.jianji.jizhang.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 备份命名与来源推断的回归测试。
 *
 * 命名规则是「云端只增不删、靠文件名区分设备与来源」的基础 —— 两个正则一旦改坏，
 * 备份列表里的设备名会全部变成 null，或者恢复时把「恢复前留底」误认成手工备份。
 */
class BackupNamingTest {

    @Test
    fun 从本机备份名里抠出设备名() {
        assertEquals("Pixel-6", deviceFromBackupName("简记-Pixel-6-manual-20260914-201530.json"))
        assertEquals("Pixel-6", deviceFromBackupName("简记-Pixel-6-auto-20260914-201530.json"))
        assertEquals("Mi-11", deviceFromBackupName("简记-Mi-11-safety-20260914-201530.json"))
        // 同名冲突时的 -n 后缀也要能吃下
        assertEquals("Mi-11", deviceFromBackupName("简记-Mi-11-auto-20260914-201530-3.json"))
    }

    @Test
    fun 从云端归档名里抠出设备名() {
        assertEquals("Pixel-6", deviceFromBackupName("Pixel-6-20260914-201530.json"))
        assertEquals("Pixel-6", deviceFromBackupName("Pixel-6-20260914-201530-2.json"))
    }

    @Test
    fun 认不出的名字返回null() {
        // 固定路径的「最新版」，本来就没有设备名
        assertNull(deviceFromBackupName("jianji-backup.json"))
        assertNull(deviceFromBackupName("随便一个名字.json"))
        assertNull(deviceFromBackupName("简记-Pixel-6-manual-20260914.json"))
    }

    @Test
    fun 来源标签推断() {
        assertEquals(BackupOrigin.SAFETY, BackupOrigin.fromTag("简记-Pixel-6-safety-20260914-201530.json"))
        // 老版本留底文件名里没有 safety，只有「恢复前留底」
        assertEquals(BackupOrigin.SAFETY, BackupOrigin.fromTag("恢复前留底-20260914-201530.json"))
        assertEquals(BackupOrigin.AUTO, BackupOrigin.fromTag("auto"))
        assertEquals(BackupOrigin.AUTO, BackupOrigin.fromTag("简记-Pixel-6-auto-20260914-201530.json"))
        // 认不出就当手工（老备份没有任何标记）
        assertEquals(BackupOrigin.MANUAL, BackupOrigin.fromTag(null))
        assertEquals(BackupOrigin.MANUAL, BackupOrigin.fromTag(""))
        assertEquals(BackupOrigin.MANUAL, BackupOrigin.fromTag("jianji-backup.json"))
    }

    @Test
    fun 来源的标签是纯ASCII且互不重复() {
        val tags = BackupOrigin.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        tags.forEach { assertTrue("标签 $it 含非 ASCII 字符", it.all { c -> c in 'a'..'z' }) }
    }

    @Test
    fun 时间戳格式固定为八位日期加六位时刻() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 14, 20, 15, 30)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("20260914-201530", backupTimestamp(cal.timeInMillis))
        assertTrue(backupTimestamp().matches(Regex("\\d{8}-\\d{6}")))
    }
}
