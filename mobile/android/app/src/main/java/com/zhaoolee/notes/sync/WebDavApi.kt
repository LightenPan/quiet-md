package com.zhaoolee.notes.sync

import java.io.IOException
import java.text.Normalizer
import java.util.Locale

data class WebDavConfig(
    val server: String,
    val username: String,
    val remoteRoot: String
) {
    companion object {
        const val DEFAULT_SERVER = "https://dav.jianguoyun.com/dav/"
        const val DEFAULT_REMOTE_ROOT = "Quiet.md"

        fun create(
            server: String = DEFAULT_SERVER,
            username: String,
            remoteRoot: String = DEFAULT_REMOTE_ROOT
        ): WebDavConfig = WebDavConfig(
            SyncPaths.normalizeServer(server),
            username.trim(),
            SyncPaths.cleanRemoteRoot(remoteRoot)
        )
    }
}

data class RemoteEntry(
    val rel: String,
    val isDir: Boolean,
    val etag: String? = null,
    val size: Long? = null,
    val modified: String? = null
)

enum class WebDavErrorKind {
    AUTH,
    NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    SERVER,
    OTHER
}

class WebDavException(
    val kind: WebDavErrorKind,
    override val message: String,
    val status: Int? = null,
    val retryAfterSeconds: Long? = null
) : IOException(message)

interface WebDavApi {
    suspend fun probe()
    suspend fun ensureRoot()
    suspend fun scan(): List<RemoteEntry>
    suspend fun get(rel: String): ByteArray

    /**
     * `null` creates a new file with `If-None-Match: *`; an empty string
     * updates without a conditional header, and any other value uses `If-Match`.
     */
    suspend fun put(rel: String, bytes: ByteArray, expectedEtag: String?): RemoteEntry
    suspend fun createDir(rel: String)
    suspend fun remove(rel: String, isDir: Boolean, expectedEtag: String?)
    suspend fun move(from: String, to: String, isDir: Boolean)
}

object SyncLimits {
    const val WARN_BYTES = 50L * 1024 * 1024
    const val MAX_BYTES = 100L * 1024 * 1024
    const val SCAN_TIMEOUT_SECONDS = 120L
    const val TRANSFER_TIMEOUT_SECONDS = 300L
    const val REQUEST_TIMEOUT_SECONDS = 60L
}

object SyncPaths {
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
    private val forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val syncExtensions = setOf("md", "markdown", "mdown", "xml", "excalidraw", "sqlite")

    fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    fun normalizeServer(input: String): String {
        val value = input.trim().ifEmpty { WebDavConfig.DEFAULT_SERVER }
        require(value.startsWith("https://") || value.startsWith("http://")) {
            "WebDAV 地址必须以 http:// 或 https:// 开头"
        }
        require(value.none { it.isWhitespace() }) { "WebDAV 地址格式不正确" }
        val authority = value.substringAfter("://").substringBefore('/')
        require(authority.isNotEmpty()) { "WebDAV 地址缺少服务器名称" }
        require('@' !in authority) { "WebDAV 地址不能包含账号或密码；请分开填写" }
        require('?' !in value && '#' !in value) { "WebDAV 地址不应包含查询参数或片段" }
        return if (value.endsWith('/')) value else "$value/"
    }

    fun cleanRemoteRoot(input: String): String {
        val normalized = input.trim().ifEmpty { WebDavConfig.DEFAULT_REMOTE_ROOT }.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "远程文件夹不能包含 .." }
        require(parts.isNotEmpty()) { "请填写坚果云里的远程文件夹名" }
        parts.forEach { requireSegment(it) }
        return parts.joinToString("/")
    }

    fun requireRelative(rel: String): String {
        require(rel.isNotBlank()) { "路径不能为空" }
        require(!rel.startsWith('/') && !rel.endsWith('/')) { "路径不能以斜杠开头或结尾：$rel" }
        rel.split('/').forEach { requireSegment(it) }
        return nfc(rel)
    }

    fun safeRelative(rel: String): Boolean = try {
        requireRelative(rel)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    fun requireSegment(segment: String) {
        require(segment.isNotEmpty() && segment != "." && segment != "..") { "文件名不能为空或是 . / .." }
        require(segment.toByteArray(Charsets.UTF_8).size <= 255) { "文件名超过 255 字节：$segment" }
        require(segment.none { it.code < 32 || it in forbidden }) { "文件名包含不可用字符：$segment" }
        require(segment == segment.trimEnd(' ', '.')) { "文件名不能以空格或句点结尾：$segment" }
        val base = segment.substringBefore('.').uppercase(Locale.ROOT)
        require(base !in reserved) { "文件名是系统保留名称：$segment" }
    }

    fun parentRel(rel: String): String = rel.substringBeforeLast('/', "")
    fun baseName(rel: String): String = rel.substringAfterLast('/')
    fun stem(rel: String): String {
        val name = baseName(rel)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extension(rel: String): String {
        val name = baseName(rel)
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    fun pathKey(rel: String): String = nfc(rel).lowercase(Locale.ROOT)

    fun isSyncedFile(rel: String, name: String): Boolean {
        if (name.startsWith('.') || name.endsWith(".tmp") || name.endsWith(".sqlite-journal")) return false
        val dirs = rel.split('/').dropLast(1)
        if (dirs.any { it.startsWith('.') && it != ".assets" }) return false
        if (rel.startsWith(".assets/")) return !rel.startsWith(".assets/thumbs/")
        return extensionOfName(name) in syncExtensions
    }

    private fun extensionOfName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    fun isContainerDir(rel: String): Boolean {
        if (rel.startsWith(".assets/thumbs")) return false
        if (!rel.contains('/')) return !rel.startsWith('.') || rel == ".assets"
        return rel.split('/').none { it.startsWith('.') }
    }

    fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            val allowed = value in 'a'.code..'z'.code ||
                value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code ||
                value == '-'.code || value == '_'.code || value == '.'.code || value == '~'.code
            if (allowed) out.append(value.toChar())
            else out.append('%').append(value.toString(16).uppercase(Locale.ROOT).padStart(2, '0'))
        }
        return out.toString()
    }

    fun conflictComponent(value: String): String {
        val cleaned = buildString {
            value.forEach { ch ->
                append(if (ch.code < 32 || ch in forbidden) ' ' else ch)
            }
        }.split(Regex("\\s+")).joinToString(" ").trim(' ', '.')
        return cleaned.ifEmpty { "Quiet.md" }
    }

    fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0) {
            if (end < value.length && value[end - 1].isHighSurrogate()) end--
            if (value.substring(0, end).toByteArray(Charsets.UTF_8).size <= maxBytes) {
                return value.substring(0, end)
            }
            end--
        }
        return ""
    }
}
