package com.jianji.jizhang.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 简记自绘图标集。
 *
 * ⚠️ 为什么不用 `material-icons-extended`：
 * 那个包里有 **11,398 个图标类**，实测会把 dex 撑大 **42 MB**
 * （本项目 classes.dex = 42.48 MB，其中 icons 占 40%）。App 实际只用了十来个图标，
 * 为一个图标付 42 MB 的代价不可接受。
 *
 * 自绘的好处不只是省体积：所有图标共用同一套笔画（1.8px + 圆角端点 + 24dp 网格），
 * 视觉天然统一 —— 混用 Material 图标的填充/线框风格会显得杂乱。
 *
 * 全部按 24×24 视口绘制，用 [androidx.compose.material3.Icon] 使用时会自动跟 tint 变色。
 */
object JizhangIcons {

    /** 首页 · 房子 */
    val Home: ImageVector = line(
        "JzHome",
        "M4 10.2 L12 3.6 L20 10.2",
        "M6.2 9.4 V19.6 H17.8 V9.4",
        "M10 19.6 V14 H14 V19.6",
    )

    /** 账单 · 列表（左侧三个圆点用极短线段 + 圆头端点画成） */
    val Bill: ImageVector = line(
        "JzBill",
        "M8 6.6 H18.4",
        "M8 12 H18.4",
        "M8 17.4 H18.4",
        "M5.2 6.6 h0.01",
        "M5.2 12 h0.01",
        "M5.2 17.4 h0.01",
    )

    /** 记一笔 · 加号 */
    val Plus: ImageVector = line(
        "JzPlus",
        "M12 5.2 V18.8",
        "M5.2 12 H18.8",
    )

    /** 统计 · 柱状图（4 根柱 + 基线） */
    val Stats: ImageVector = line(
        "JzStats",
        "M3.6 19.8 H20.4",
        "M6.6 19.8 V13.4",
        "M10.6 19.8 V8.6",
        "M14.6 19.8 V15.4",
        "M18.6 19.8 V5.8",
    )

    /** 设置 · 三条滑块（点位置错开，比齿轮更现代也更好画） */
    val Settings: ImageVector = line(
        "JzSettings",
        "M4 7.2 H8.6", "M12.6 7.2 H20", "M10.6 7.2 h0.01",
        "M4 12 H14.6", "M18.6 12 H20", "M16.6 12 h0.01",
        "M4 16.8 H6.4", "M10.4 16.8 H20", "M8.4 16.8 h0.01",
    )

    /** 切换首页风格 · 调色板（圆环描边 + 4 个实心色点） */
    val Palette: ImageVector = icon(
        name = "JzPalette",
        strokePaths = listOf(
            "M12 3.4 A8.6 8.6 0 1 1 12 20.6 A8.6 8.6 0 1 1 12 3.4",
        ),
        fillPaths = listOf(
            circle(8.6f, 9.6f, 1.25f),
            circle(15.4f, 9.6f, 1.25f),
            circle(9.2f, 15.2f, 1.25f),
            circle(15.0f, 14.6f, 1.25f),
        ),
    )

    val ChevronLeft: ImageVector = line("JzChevronLeft", "M14.4 6.2 L8.4 12 L14.4 17.8")

    val ChevronRight: ImageVector = line("JzChevronRight", "M9.6 6.2 L15.6 12 L9.6 17.8")

    /** 返回 · 箭头（比 Chevron 多一段横线，语义更明确） */
    val ArrowBack: ImageVector = line(
        "JzArrowBack",
        "M19 12 H5.6",
        "M11.6 5.6 L5.2 12 L11.6 18.4",
    )

    val Close: ImageVector = line(
        "JzClose",
        "M6.2 6.2 L17.8 17.8",
        "M17.8 6.2 L6.2 17.8",
    )

    val Check: ImageVector = line("JzCheck", "M5.4 12.6 L10 17.2 L18.6 6.8")

    val Search: ImageVector = line(
        "JzSearch",
        "M10.6 4.4 A6.2 6.2 0 1 1 10.6 16.8 A6.2 6.2 0 1 1 10.6 4.4",
        "M15.2 15.2 L20 20",
    )

    val Edit: ImageVector = line(
        "JzEdit",
        "M4.6 19.4 V15.2 L15.6 4.2 L19.8 8.4 L8.8 19.4 Z",
        "M13.4 6.4 L17.6 10.6",
    )

    val Trash: ImageVector = line(
        "JzTrash",
        "M4.6 7 H19.4",
        "M9.4 7 V4.6 H14.6 V7",
        "M6.6 7 L7.6 20 H16.4 L17.4 7",
        "M10.4 10.6 V16.6",
        "M13.6 10.6 V16.6",
    )

    /** 云备份 · 上传箭头 + 底座 */
    val CloudUpload: ImageVector = line(
        "JzCloudUpload",
        "M4.6 18.2 A4.1 4.1 0 0 1 5.9 10.2 A5.6 5.6 0 0 1 16.9 11.4 A3.6 3.6 0 0 1 18.9 18.2 Z",
        "M12 18.2 V9.4",
        "M8.8 12.4 L12 9.2 L15.2 12.4",
    )

    /** 账户 · 钱包 */
    val Wallet: ImageVector = icon(
        name = "JzWallet",
        strokePaths = listOf(
            "M3.8 6.8 H20.2 V19.2 H3.8 Z",
            "M14.8 11.4 H20.4 V14.6 H14.8 Z",
            "M15.6 13 h0.01",
            "M3.8 10 H20.2",
        ),
    )

    /** 日历 */
    val Calendar: ImageVector = line(
        "JzCalendar",
        "M4.4 6.6 H19.6 V19.6 H4.4 Z",
        "M8.2 4 V6.6",
        "M15.8 4 V6.6",
        "M4.4 10.6 H19.6",
    )

    val MoreVert: ImageVector = line(
        "JzMoreVert",
        "M12 6.4 h0.01",
        "M12 12 h0.01",
        "M12 17.6 h0.01",
    )

    val Empty: ImageVector = line(
        "JzEmpty",
        "M4.4 8.4 H19.6 V18.6 H4.4 Z",
        "M4.4 12.2 H9 L10.2 14 H13.8 L15 12.2 H19.6",
    )

    /** 数据备份 · 导出（托盘 + 上箭头） */
    val Upload: ImageVector = line(
        "JzUpload",
        "M4.6 15.4 V19.8 H19.4 V15.4",
        "M12 15.2 V4.2",
        "M7.8 8.4 L12 4.2 L16.2 8.4",
    )

    /** 数据备份 · 导入（托盘 + 下箭头） */
    val Download: ImageVector = line(
        "JzDownload",
        "M4.6 15.4 V19.8 H19.4 V15.4",
        "M12 4.2 V15.2",
        "M7.8 11 L12 15.2 L16.2 11",
    )

    /** 备份文件 · 文档（列表行左侧标识） */
    val Doc: ImageVector = line(
        "JzDoc",
        "M6.6 3.6 H13.6 L17.6 7.6 V20.4 H6.6 Z",
        "M13.6 3.6 V7.6 H17.6",
        "M9.4 12.2 H14.8",
        "M9.4 16 H12.8",
    )

    /** 空态 · 文件夹（放在大尺寸空态里，圆角与外框都留足） */
    val Folder: ImageVector = line(
        "JzFolder",
        "M3.6 8.4 A1.8 1.8 0 0 1 5.4 6.6 H9.2 L11 9 H18.6 A1.8 1.8 0 0 1 20.4 10.8 V17.6 " +
            "A1.8 1.8 0 0 1 18.6 19.4 H5.4 A1.8 1.8 0 0 1 3.6 17.6 Z",
    )

    /** 云端备份 Tab · 云 */
    val Cloud: ImageVector = line(
        "JzCloud",
        "M4.6 18.2 A4.1 4.1 0 0 1 5.9 10.2 A5.6 5.6 0 0 1 16.9 11.4 A3.6 3.6 0 0 1 18.9 18.2 Z",
    )

    /**
     * 通用 · 刷新。顺时针开环 + 端点箭头：
     * 缺口留在正上方，箭头方向 = 终点处的切向（右上），否则箭头会「横着长」。
     */
    val Rotate: ImageVector = line(
        "JzRotate",
        "M15.6 5.8 A7.2 7.2 0 1 1 8.4 5.8",
        "M6.3 9.0 L8.4 5.8 L4.6 6.0",
    )

    /** 密码 · 睁眼（点了就显示明文）。 */
    val Eye: ImageVector = line(
        "JzEye",
        "M2.6 12 C5.6 7.3 9.0 5.3 12 5.3 C15 5.3 18.4 7.3 21.4 12 " +
            "C18.4 16.7 15 18.7 12 18.7 C9 18.7 5.6 16.7 2.6 12 Z",
        circle(12f, 12f, 2.7f),
    )

    /** 密码 · 闭眼（默认态，一眼看去就知道被遮住了）。 */
    val EyeOff: ImageVector = line(
        "JzEyeOff",
        "M2.6 12 C5.6 7.3 9.0 5.3 12 5.3 C15 5.3 18.4 7.3 21.4 12 " +
            "C18.4 16.7 15 18.7 12 18.7 C9 18.7 5.6 16.7 2.6 12 Z",
        circle(12f, 12f, 2.7f),
        "M4.4 4.4 L19.6 19.6",
    )

    /** 凭据区 · 锁。 */
    val Lock: ImageVector = line(
        "JzLock",
        "M5.6 11.2 h12.8 a1.6 1.6 0 0 1 1.6 1.6 v5.6 a1.6 1.6 0 0 1 -1.6 1.6 " +
            "h-12.8 a1.6 1.6 0 0 1 -1.6 -1.6 v-5.6 a1.6 1.6 0 0 1 1.6 -1.6 Z",
        "M8.4 11.2 V8.4 a3.6 3.6 0 0 1 7.2 0 v2.8",
        "M12 14.6 v2.2",
    )

    /** 设备名片段 · 手机。 */
    val Device: ImageVector = line(
        "JzDevice",
        "M7.6 2.9 h8.8 a2.1 2.1 0 0 1 2.1 2.1 v14 a2.1 2.1 0 0 1 -2.1 2.1 " +
            "h-8.8 a2.1 2.1 0 0 1 -2.1 -2.1 v-14 a2.1 2.1 0 0 1 2.1 -2.1 Z",
        "M10.6 18.1 h2.8",
    )
}

/* ---------------- 构建辅助 ---------------- */

private const val STROKE_WIDTH = 1.8f

/** 一个实心圆的 path（两段半圆弧拼成整圆）。 */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy a$r $r 0 1 0 ${r * 2} 0 a$r $r 0 1 0 ${-r * 2} 0 Z"

/** 纯描边图标。 */
private fun line(name: String, vararg strokePaths: String): ImageVector =
    icon(name = name, strokePaths = strokePaths.toList())

private fun icon(
    name: String,
    strokePaths: List<String> = emptyList(),
    fillPaths: List<String> = emptyList(),
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    strokePaths.forEach { d ->
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    fillPaths.forEach { d ->
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }
    return builder.build()
}
