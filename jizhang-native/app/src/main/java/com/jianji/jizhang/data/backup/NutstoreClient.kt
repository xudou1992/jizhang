package com.jianji.jizhang.data.backup

import android.util.Base64
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
// 注意：不要 import java.nio.charset.Charsets（JDK 里不存在这个类）。
// 直接用 kotlin.text.Charsets.UTF_8，它是 stdlib 的，无需 import。

/** PROPFIND 列目录返回的单个远端文件。 */
data class RemoteFile(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * 坚果云 WebDAV 客户端。端点固定 `https://dav.jianguoyun.com/dav/`，Basic Auth。
 *
 * 参考旧版（ivy-wallet）的 WebDavClient 改写：去掉 Hilt、去掉 Ivy 专有类型。
 * 所有网络调用都包了 [withContext(Dispatchers.IO)] + try/catch，绝不向外抛异常。
 *
 * 关于写 JSON 的方式：项目没引入 ktor-serialization，不能 setBody(对象) 自动序列化，
 * 所以一律手动 `Json.encodeToString` 成 ByteArray 再 `setBody(bytes)`。
 */
class NutstoreClient(
    private val email: String,
    private val password: String,
) {
    private val client: HttpClient = HttpClient(OkHttp) {
        // expectSuccess=false 让 404/405/409 等状态码不抛异常，自己判断。
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /** 用完释放连接。 */
    fun close() = client.close()

    private fun authHeader(): String {
        val cred = "$email:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(cred, Base64.NO_WRAP)
    }

    /** 把远程路径每一段 URL 编码（中文目录安全）；"+" 换成 "%20" 规避坚果云把空格当加号。 */
    private fun buildUrl(remotePath: String): String {
        val trimmed = remotePath.trimStart('/')
        val encoded = trimmed.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
        return BASE + encoded
    }

    /** 上传一个文件。 */
    suspend fun putFile(remotePath: String, bytes: ByteArray): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val response: HttpResponse = client.put(buildUrl(remotePath)) {
                header(HttpHeaders.Authorization, authHeader())
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(bytes)
            }
            val code = response.status.value
            if (code !in 200..299) error("上传失败：HTTP $code")
        }
    }

    /** 下载一个文件。HTTP 404 当作「不存在」，返回 success(null)，不算错误。 */
    suspend fun getFile(remotePath: String): Result<ByteArray?> = runCatching {
        withContext(Dispatchers.IO) {
            val response: HttpResponse = client.get(buildUrl(remotePath)) {
                header(HttpHeaders.Authorization, authHeader())
            }
            when (val code = response.status.value) {
                404 -> null
                in 200..299 -> response.readBytes()
                else -> error("下载失败：HTTP $code")
            }
        }
    }

    /**
     * 列目录（PROPFIND，Depth:1）。404 视为空目录；只返回文件，不含目录本身。
     * 解析失败的行跳过，不让整个列表页崩掉。
     */
    suspend fun listFiles(dir: String): Result<List<RemoteFile>> = runCatching {
        withContext(Dispatchers.IO) {
            val response: HttpResponse = client.request(buildUrl(dir)) {
                method = PROPFIND
                header(HttpHeaders.Authorization, authHeader())
                header("Depth", "1")
            }
            when (val code = response.status.value) {
                404 -> emptyList()
                !in 200..299 -> error("列目录失败：HTTP $code")
                else -> parseMultistatus(response.bodyAsText())
            }
        }
    }

    /** 删除远端文件。404 也算成功（目标已经不在了）。 */
    suspend fun deleteFile(remotePath: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val response: HttpResponse = client.delete(buildUrl(remotePath)) {
                header(HttpHeaders.Authorization, authHeader())
            }
            val code = response.status.value
            if (code !in 200..299 && code != 404) error("删除失败：HTTP $code")
        }
    }

    /**
     * 创建目录（WebDAV MKCOL）。201 成功；405/409 表示目录已存在，也视为成功。
     */
    suspend fun makeDir(dir: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val response: HttpResponse = client.request(buildUrl(dir)) {
                method = MKCOL
                header(HttpHeaders.Authorization, authHeader())
            }
            val code = response.status.value
            if (code !in 200..299 && code != 405 && code != 409) {
                error("创建目录失败：HTTP $code")
            }
        }
    }

    /** 解析 WebDAV 207 multistatus XML。 */
    private fun parseMultistatus(xml: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var href: String? = null
            var size = 0L
            var modified: String? = null
            var creation: String? = null
            var collecting = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.substringAfter(':').lowercase()) {
                            "response" -> {
                                collecting = true
                                href = null; size = 0L; modified = null; creation = null
                            }
                            "href" -> if (collecting && href == null) href = parser.nextText()
                            "getcontentlength" -> if (collecting) size = parser.nextText().toLongOrNull() ?: 0L
                            "getlastmodified" -> if (collecting && modified == null) modified = parser.nextText()
                            "creationdate" -> if (collecting && creation == null) creation = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (collecting && parser.name.substringAfter(':').lowercase() == "response") {
                            collecting = false
                            val h = href
                            // href 以 / 结尾的是目录本身，跳过；只收文件。
                            if (h != null && !h.endsWith("/")) {
                                val raw = h.substringAfter("/dav/", missingDelimiterValue = "")
                                if (raw.isNotEmpty()) {
                                    val path = URLDecoder.decode(raw, "UTF-8")
                                    val name = path.trim('/').substringAfterLast('/')
                                    if (name.isNotBlank()) {
                                        out += RemoteFile(
                                            path = "/" + path.trim('/'),
                                            name = name,
                                            sizeBytes = size,
                                            lastModified = parseRemoteTime(modified, creation, name),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // XML 畸形等异常：返回已解析到的部分，尽量不白屏。
        }
        return out
    }

    companion object {
        private const val BASE = "https://dav.jianguoyun.com/dav/"
        private val MKCOL = HttpMethod("MKCOL")
        private val PROPFIND = HttpMethod("PROPFIND")

        /**
         * 远端时间兜底链：RFC1123（getlastmodified）→ ISO8601（creationdate）→
         * 文件名内嵌时间戳（`<设备>-yyyyMMdd-HHmmss.json` 或老格式 `jianji-yyyyMMdd_HHmmss.json`）
         * → 0（未知）。
         */
        fun parseRemoteTime(vararg candidates: String?): Long {
            for (c in candidates) {
                if (c.isNullOrBlank()) continue
                try {
                    return SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(c)?.time ?: continue
                } catch (_: Exception) { }
                try {
                    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        .parse(c.take(19))?.time ?: continue
                } catch (_: Exception) { }
                // 新命名用 `-`，老命名用 `_`，两种都认。
                Regex("(\\d{8})[_-](\\d{6})").find(c)?.let { m ->
                    try {
                        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                            .parse(m.groupValues[1] + "_" + m.groupValues[2])?.time ?: 0L
                    } catch (_: Exception) { }
                }
            }
            return 0L
        }
    }
}
