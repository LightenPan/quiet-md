package com.zhaoolee.notes.library

import java.text.Normalizer
import java.util.Locale

internal object PathRules {
    const val MAX_TITLE_CHARS = 120
    const val MAX_TITLE_PARAM = 40
    const val MAX_COMPONENT_BYTES = 180
    const val UNTITLED = "无标题"
    const val DEFAULT_FOLDER = "默认"
    val editableExtensions = setOf("md", "markdown", "mdown")
    val listedExtensions = editableExtensions + setOf("xml", "excalidraw", "sqlite")

    private val forbidden = Regex("""[\\/:*?"<>|]""")
    private val whitespace = Regex("""\s+""")
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    fun sanitizeTitle(raw: String): String = sanitizeComponent(raw, UNTITLED, MAX_TITLE_CHARS)

    fun sanitizeFolder(raw: String): String =
        sanitizeComponent(raw, DEFAULT_FOLDER, MAX_TITLE_CHARS)

    fun sanitizeComponent(raw: String, fallback: String, max: Int): String {
        var value = nfc(raw).trim()
        value = forbidden.replace(value, " ")
        value = value.filterNot { it.isISOControl() }
        value = whitespace.replace(value, " ")
        value = value.trim().trim('.', ' ').trimStart('.')
        value = truncateUtf8(truncateCodePoints(value, max), MAX_COMPONENT_BYTES).trimEnd('.', ' ')
        if (value.isEmpty() || value == "." || value == "..") return fallback
        val base = value.substringBefore('.')
        if (base.uppercase(Locale.ROOT) in reserved) value = "${value}_"
        return value
    }

    fun normalizeFolderInput(raw: String): String {
        val parts = raw.replace('\\', '/').split('/')
        val clean = ArrayList<String>()
        for (part in parts) {
            val value = part.trim()
            if (value.isEmpty() || value == ".") continue
            require(value != "..") { "文件夹不能包含 .." }
            clean.add(sanitizeFolder(value))
        }
        return clean.joinToString("/")
    }

    fun validateRelativePath(raw: String): String {
        require(raw.isNotBlank()) { "便签路径不能为空" }
        require(!raw.startsWith('/') && !raw.endsWith('/')) { "便签路径格式不正确：$raw" }
        require('\\' !in raw) { "便签路径不能包含反斜杠：$raw" }
        val normalized = nfc(raw)
        val parts = normalized.split('/')
        require(parts.all { it.isNotBlank() && it != "." && it != ".." }) { "便签路径不安全：$raw" }
        require(parts.none { it.startsWith('.') }) { "便签路径不能指向隐藏目录：$raw" }
        require(parts.all { !forbidden.containsMatchIn(it) && it.none(Char::isISOControl) }) {
            "便签路径包含非法字符：$raw"
        }
        return parts.joinToString("/")
    }

    fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isEditable(name: String): Boolean = extension(name) in editableExtensions

    fun isListed(name: String): Boolean = extension(name) in listedExtensions

    fun titleOf(name: String): String = name.substringBeforeLast('.', name)

    fun folderOf(relativePath: String): String =
        relativePath.substringBeforeLast('/', "")

    fun parentOf(folder: String): String = folder.substringBeforeLast('/', "")

    fun truncateCodePoints(value: String, max: Int): String {
        if (value.codePointCount(0, value.length) <= max) return value
        return value.substring(0, value.offsetByCodePoints(0, max))
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
