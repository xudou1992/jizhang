package com.jianji.jizhang.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 远端时间兜底链的回归测试：RFC1123 → ISO8601 → 文件名内嵌时间戳 → 0。
 *
 * 这条链子决定了备份列表的排序。任意一环退化成「返回 0」，那些备份就会全部
 * 沉到列表最底下，用户会以为备份丢了。
 */
class RemoteTimeTest {

    /** 用系统默认时区把「本地墙上时间」换算成毫秒，与被测实现的口径一致。 */
    private fun local(literal: String): Long =
        LocalDateTime.parse(literal).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun 优先认RFC1123() {
        // 2026-09-14 是周一
        val expect = Instant.parse("2026-09-14T20:15:30Z").toEpochMilli()
        assertEquals(expect, NutstoreClient.parseRemoteTime("Mon, 14 Sep 2026 20:15:30 GMT"))
    }

    @Test
    fun 认不出RFC1123时退到ISO8601() {
        assertEquals(
            local("2026-09-14T20:15:30"),
            NutstoreClient.parseRemoteTime("2026-09-14T20:15:30"),
        )
    }

    @Test
    fun 再退到文件名里的时间戳() {
        val expect = local("2026-09-14T20:15:30")
        // 新命名用 -，老命名用 _
        assertEquals(expect, NutstoreClient.parseRemoteTime("Pixel-6-20260914-201530.json"))
        assertEquals(expect, NutstoreClient.parseRemoteTime("jianji-20260914_201530.json"))
    }

    @Test
    fun 前一个候选认不出时会继续试下一个() {
        // getlastmodified 是垃圾、creationdate 有效 —— 必须跳到第二个候选
        assertEquals(
            local("2026-09-14T20:15:30"),
            NutstoreClient.parseRemoteTime("垃圾值", "2026-09-14T20:15:30"),
        )
    }

    @Test
    fun 全都认不出时返回零() {
        assertEquals(0L, NutstoreClient.parseRemoteTime("没时间", null, ""))
        assertEquals(0L, NutstoreClient.parseRemoteTime())
        assertEquals(0L, NutstoreClient.parseRemoteTime(null, null))
    }
}
