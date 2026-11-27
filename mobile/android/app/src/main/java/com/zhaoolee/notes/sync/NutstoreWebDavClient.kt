package com.zhaoolee.notes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop>
<d:displayname/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/><d:getetag/>
</d:prop></d:propfind>"""
private const val XML_RESPONSE_LIMIT = 16L * 1024 * 1024

class NutstoreWebDavClient(
    config: WebDavConfig,
    password: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val maxDownloadBytes: Long = SyncLimits.MAX_BYTES,
    private val requestIntervalMs: Long = 0,
    private val requestBudget: Int? = null
) : WebDavApi {
    private val config = config.copy(
        server = SyncPaths.normalizeServer(config.server),
        remoteRoot = SyncPaths.cleanRemoteRoot(config.remoteRoot)
    )
    private val authorization = "Basic " + Base64.getEncoder().encodeToString(
        "${this.config.username}:$password".toByteArray(Charsets.UTF_8)
    )
    private val rootParts = this.config.remoteRoot.split('/')
    private val base = buildString {
        append(this@NutstoreWebDavClient.config.server.trimEnd('/'))
        rootParts.forEach { append('/').append(SyncPaths.encodeSegment(it)) }
        append('/')
    }
    private val basePath = buildString {
        append(decodedUrlPath(this@NutstoreWebDavClient.config.server).trimEnd('/'))
        rootParts.forEach { append('/').append(it) }
        append('/')
    }
    private val serverBasePath = decodedUrlPath(this@NutstoreWebDavClient.config.server).trimEnd('/')
    private val paceLock = Any()
    private val budgetLock = Any()
    private var lastRequestAtNanos = 0L
    private var requestCount = 0

    override suspend fun probe() = withContext(Dispatchers.IO) {
        propfind("", "0", true)
        Unit
    }

    override suspend fun ensureRoot() = withContext(Dispatchers.IO) {
        try {
            propfind("", "0", true)
            return@withContext
        } catch (e: WebDavException) {
            if (e.kind != WebDavErrorKind.NOT_FOUND) throw e
        }
        for (index in rootParts.indices) {
            val rel = rootParts.take(index + 1).joinToString("/")
            try {
                propfindAt(serverUrl(rel, true), "0")
            } catch (e: WebDavException) {
                if (e.kind != WebDavErrorKind.NOT_FOUND) throw e
                try {
                    send(Request.Builder().url(serverUrl(rel, true)).method("MKCOL", null), SyncLimits.REQUEST_TIMEOUT_SECONDS)
                } catch (create: WebDavException) {
                    if (create.status != 405) throw create
                }
            }
        }
        propfind("", "0", true)
        Unit
    }

    override suspend fun scan(): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val deep = try {
            propfind("", "infinity", true)
        } catch (e: WebDavException) {
            if (e.status in setOf(400, 403, 405, 501, 507) ||
                e.kind == WebDavErrorKind.OTHER || e.kind == WebDavErrorKind.NOT_FOUND
            ) {
                return@withContext scanLevels()
            }
            throw e
        }
        if (deep.size >= 750) return@withContext scanLevels()
        // Nutstore answers `Depth: infinity` with one level only. A result
        // holding folders but nothing below them is not the whole tree.
        if (deep.any { it.isDir } && deep.none { it.rel.contains('/') }) return@withContext scanLevels()
        deep
    }

    suspend fun browse(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val normalized = if (path.isEmpty()) "" else SyncPaths.requireRelative(path)
        val entries = propfindAt(serverUrl(normalized, true), "1", serverBasePath)
        if (entries.size >= 750) {
            throw WebDavException(
                WebDavErrorKind.OTHER,
                "坚果云单个文件夹超过 750 项，WebDAV 无法完整分页；请选择下一级文件夹"
            )
        }
        entries.asSequence()
            .filter { it.rel != normalized && SyncPaths.parentRel(it.rel) == normalized }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.rel.lowercase(Locale.ROOT) })
            .toList()
    }

    override suspend fun get(rel: String): ByteArray = withContext(Dispatchers.IO) {
        val response = send(
            Request.Builder().url(url(rel, false)).get(),
            SyncLimits.TRANSFER_TIMEOUT_SECONDS,
            maxDownloadBytes
        )
        if (response.body.size > maxDownloadBytes) throw SyncException("$rel 超过同步上限")
        response.body
    }

    override suspend fun put(rel: String, bytes: ByteArray, expectedEtag: String?): RemoteEntry =
        withContext(Dispatchers.IO) {
            if (bytes.size > SyncLimits.MAX_BYTES) throw SyncException("$rel 超过同步上限")
            val builder = Request.Builder()
                .url(url(rel, false))
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            when {
                expectedEtag == null -> builder.header("If-None-Match", "*")
                expectedEtag.isNotEmpty() -> builder.header("If-Match", expectedEtag)
            }
            send(builder, SyncLimits.TRANSFER_TIMEOUT_SECONDS)
            stat(rel)
        }

    override suspend fun createDir(rel: String) = withContext(Dispatchers.IO) {
        try {
            send(Request.Builder().url(url(rel, true)).method("MKCOL", null), SyncLimits.REQUEST_TIMEOUT_SECONDS)
        } catch (e: WebDavException) {
            if (e.status != 405) throw e
        }
        Unit
    }

    override suspend fun remove(rel: String, isDir: Boolean, expectedEtag: String?) =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url(rel, isDir)).delete()
            if (!isDir && !expectedEtag.isNullOrEmpty()) builder.header("If-Match", expectedEtag)
            try {
                send(builder, SyncLimits.REQUEST_TIMEOUT_SECONDS)
            } catch (e: WebDavException) {
                if (e.kind != WebDavErrorKind.NOT_FOUND) throw e
            }
            Unit
        }

    override suspend fun move(from: String, to: String, isDir: Boolean) =
        withContext(Dispatchers.IO) {
            send(
                Request.Builder()
                    .url(url(from, isDir))
                    .method("MOVE", null)
                    .header("Destination", url(to, isDir))
                    .header("Overwrite", "F"),
                SyncLimits.REQUEST_TIMEOUT_SECONDS
            )
            Unit
        }

    private fun scanLevels(): List<RemoteEntry> {
        val out = linkedMapOf<String, RemoteEntry>()
        val queue = ArrayDeque<String>()
        queue.add("")
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val entries = propfind(dir, "1", true)
            if (entries.size >= 750) {
                throw WebDavException(
                    WebDavErrorKind.OTHER,
                    "坚果云单个文件夹超过 750 项，WebDAV 无法完整分页；请拆分文件夹"
                )
            }
            for (entry in entries) {
                if (entry.rel == dir) continue
                out[entry.rel] = entry
                if (entry.isDir && entry.rel !in queue) queue.add(entry.rel)
            }
        }
        return out.values.toList()
    }

    private fun stat(rel: String): RemoteEntry {
        val entries = propfind(rel, "0", false)
        return entries.firstOrNull { it.rel == rel }
            ?: throw WebDavException(WebDavErrorKind.OTHER, "WebDAV 没有返回文件属性：$rel")
    }

    private fun propfind(rel: String, depth: String, dir: Boolean): List<RemoteEntry> =
        propfindAt(url(rel, dir), depth)

    private fun propfindAt(target: String, depth: String, base: String = basePath): List<RemoteEntry> {
        val response = send(
            Request.Builder()
                .url(target)
                .method(
                    "PROPFIND",
                    PROPFIND_BODY.toByteArray(Charsets.UTF_8)
                        .toRequestBody("application/xml; charset=utf-8".toMediaType())
                )
                .header("Depth", depth),
            SyncLimits.SCAN_TIMEOUT_SECONDS
        )
        return parseMultiStatus(response.body, base)
    }

    private fun send(
        builder: Request.Builder,
        timeoutSeconds: Long,
        maxBodyBytes: Long = XML_RESPONSE_LIMIT
    ): RawResponse {
        reserveRequest()
        paceRequest()
        val request = builder
            .header("Authorization", authorization)
            .header("Accept", "application/xml, application/octet-stream")
            .header("User-Agent", "Quiet.md Android")
            .build()
        val callClient = client.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val response = callClient.newCall(request).execute()
        response.use {
            val body = it.body?.let { responseBody -> readBodyLimited(responseBody, maxBodyBytes) } ?: ByteArray(0)
            val headers = buildMap {
                for (i in 0 until it.headers.size) {
                    put(it.headers.name(i).lowercase(Locale.ROOT), it.headers.value(i))
                }
            }
            if (it.code !in 200..299) throw mapError(it.code, headers, body)
            return RawResponse(it.code, headers, body)
        }
    }

    private fun reserveRequest() {
        val budget = requestBudget ?: return
        synchronized(budgetLock) {
            if (requestCount >= budget) {
                throw SyncPausedException("本轮已达到坚果云请求上限，进度已保存；请约 30 分钟后再继续")
            }
            requestCount++
        }
    }

    private fun paceRequest() {
        if (requestIntervalMs <= 0) return
        synchronized(paceLock) {
            if (lastRequestAtNanos != 0L) {
                val interval = requestIntervalMs * 1_000_000L
                val elapsed = System.nanoTime() - lastRequestAtNanos
                if (elapsed < interval) {
                    Thread.sleep(((interval - elapsed) / 1_000_000L).coerceAtLeast(1L))
                }
            }
            lastRequestAtNanos = System.nanoTime()
        }
    }

    private fun readBodyLimited(body: ResponseBody, maxBytes: Long): ByteArray {
        val declared = body.contentLength()
        if (declared > maxBytes) throw SyncException("WebDAV 响应超过同步上限")
        val initial = when {
            declared > 0 -> minOf(declared, 1024L * 1024).toInt()
            else -> 8192
        }
        val out = ByteArrayOutputStream(initial)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw SyncException("WebDAV 响应超过同步上限")
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    private fun parseMultiStatus(body: ByteArray, base: String): List<RemoteEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }
        val document = try {
            factory.newDocumentBuilder()
                .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
                .parse(ByteArrayInputStream(body))
        } catch (e: Exception) {
            throw WebDavException(WebDavErrorKind.OTHER, "WebDAV XML 无法解析：${e.message ?: "格式错误"}")
        }
        val responses = document.getElementsByTagNameNS("*", "response")
        val out = linkedMapOf<String, RemoteEntry>()
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            val href = text(response, "href") ?: continue
            val rel = hrefToRelativePath(href, base) ?: continue
            if (rel.isEmpty()) continue
            if (!SyncPaths.safeRelative(rel)) {
                throw WebDavException(WebDavErrorKind.OTHER, "WebDAV 返回了不安全的路径：$rel")
            }
            val resourceType = elements(response, "resourcetype").firstOrNull()
            val isDir = resourceType != null &&
                resourceType.getElementsByTagNameNS("*", "collection").length > 0
            val entry = RemoteEntry(
                rel = SyncPaths.nfc(rel),
                isDir = isDir,
                etag = text(response, "getetag")?.takeIf { it.isNotEmpty() },
                size = text(response, "getcontentlength")?.toLongOrNull(),
                modified = text(response, "getlastmodified")?.takeIf { it.isNotEmpty() }
            )
            val old = out[entry.rel]
            if (old != null && old.isDir != entry.isDir) {
                throw WebDavException(WebDavErrorKind.OTHER, "WebDAV 同一路径同时是文件和文件夹：${entry.rel}")
            }
            out[entry.rel] = entry
        }
        return out.values.toList()
    }

    private fun elements(parent: Element, localName: String): List<Element> {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        return buildList(nodes.length) {
            for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(::add)
        }
    }

    private fun text(parent: Element, localName: String): String? =
        elements(parent, localName).firstOrNull()?.textContent?.trim()

    private fun hrefToRelativePath(href: String, basePath: String): String? {
        var path = href.trim()
        if (path.isEmpty()) return null
        if ("://" in path) {
            val rest = path.substringAfter("://")
            path = rest.substringAfter('/', "").let { if (it.isEmpty()) "/" else "/$it" }
        }
        path = path.substringBefore('?').substringBefore('#')
        if (!path.startsWith('/')) path = "/$path"
        val decoded = percentDecode(path)
        val base = basePath.trimEnd('/')
        if (decoded == base) return ""
        if (!decoded.startsWith("$base/")) return null
        return SyncPaths.nfc(decoded.substring(base.length + 1).trim('/'))
    }

    private fun url(rel: String, dir: Boolean): String {
        val normalized = if (rel.isEmpty()) "" else SyncPaths.requireRelative(rel)
        val out = StringBuilder(base.trimEnd('/'))
        normalized.split('/').filter { it.isNotEmpty() }.forEach {
            out.append('/').append(SyncPaths.encodeSegment(it))
        }
        if (dir) out.append('/')
        return out.toString()
    }

    private fun serverUrl(rel: String, dir: Boolean): String {
        val out = StringBuilder(config.server.trimEnd('/'))
        if (rel.isNotEmpty()) {
            SyncPaths.requireRelative(rel).split('/').forEach {
                out.append('/').append(SyncPaths.encodeSegment(it))
            }
        }
        if (dir) out.append('/')
        return out.toString()
    }

    private fun decodedUrlPath(url: String): String {
        val afterScheme = if ("://" in url) url.substringAfter("://") else url
        val slash = afterScheme.indexOf('/')
        val path = if (slash >= 0) afterScheme.substring(slash) else "/"
        val clean = path.substringBefore('?').substringBefore('#')
        return percentDecode(clean)
    }

    private fun percentDecode(value: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    bytes.write(byte)
                    i += 3
                    continue
                }
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun mapError(status: Int, headers: Map<String, String>, body: ByteArray): WebDavException {
        val kind = when (status) {
            401, 403 -> WebDavErrorKind.AUTH
            404 -> WebDavErrorKind.NOT_FOUND
            409, 412, 423 -> WebDavErrorKind.CONFLICT
            429, 503 -> WebDavErrorKind.RATE_LIMITED
            in 500..599 -> WebDavErrorKind.SERVER
            else -> WebDavErrorKind.OTHER
        }
        val detail = body.toString(Charsets.UTF_8).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith('<') && !it.startsWith("<?xml") }
            ?.take(240)
        val message = when {
            status == 429 || status == 503 -> "坚果云暂时限制了访问频率，请稍后再试"
            detail != null -> detail
            else -> "坚果云 WebDAV 返回 HTTP $status"
        }
        return WebDavException(
            kind,
            message,
            status,
            headers["retry-after"]?.toLongOrNull()
        )
    }

    private data class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray
    )
}
