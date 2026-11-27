package com.zhaoolee.notes.sync

class FakeWebDav : WebDavApi {
    data class StoredFile(val bytes: ByteArray, val etag: String)

    val files = linkedMapOf<String, StoredFile>()
    val dirs = linkedSetOf<String>()
    val moves = mutableListOf<Pair<String, String>>()
    var getCalls = 0

    fun putFile(rel: String, bytes: ByteArray, etag: String? = null) {
        val normalized = SyncPaths.requireRelative(rel)
        addParents(normalized)
        val next = etag ?: "\"e${files.size + 1}-${bytes.size}\""
        files[normalized] = StoredFile(bytes, next)
    }

    fun replaceFile(rel: String, bytes: ByteArray) {
        putFile(rel, bytes)
    }

    fun deleteFile(rel: String) {
        files.remove(rel)
    }

    fun clearFiles() {
        files.clear()
    }

    override suspend fun probe() = Unit

    override suspend fun ensureRoot() = Unit

    override suspend fun scan(): List<RemoteEntry> {
        val out = mutableListOf<RemoteEntry>()
        for (dir in dirs) out.add(RemoteEntry(dir, true))
        for ((rel, file) in files) {
            out.add(RemoteEntry(rel, false, file.etag, file.bytes.size.toLong(), null))
        }
        return out
    }

    override suspend fun get(rel: String): ByteArray {
        getCalls++
        val file = files[rel] ?: throw WebDavException(WebDavErrorKind.NOT_FOUND, "missing $rel", 404)
        return file.bytes.copyOf()
    }

    override suspend fun put(rel: String, bytes: ByteArray, expectedEtag: String?): RemoteEntry {
        val normalized = SyncPaths.requireRelative(rel)
        val old = files[normalized]
        if (expectedEtag == null && old != null) {
            throw WebDavException(WebDavErrorKind.CONFLICT, "destination exists", 412)
        }
        if (!expectedEtag.isNullOrEmpty() && old?.etag != expectedEtag) {
            throw WebDavException(WebDavErrorKind.CONFLICT, "etag changed", 412)
        }
        addParents(normalized)
        val etag = "\"e${files.size + 1}-${System.nanoTime()}\""
        files[normalized] = StoredFile(bytes.copyOf(), etag)
        return RemoteEntry(
            normalized,
            false,
            etag,
            bytes.size.toLong(),
            null
        )
    }

    override suspend fun createDir(rel: String) {
        val normalized = SyncPaths.requireRelative(rel)
        addParents(normalized)
        dirs.add(normalized)
    }

    override suspend fun remove(rel: String, isDir: Boolean, expectedEtag: String?) {
        if (isDir) {
            files.keys.filter { it == rel || it.startsWith("$rel/") }.forEach(files::remove)
            dirs.filter { it == rel || it.startsWith("$rel/") }.forEach(dirs::remove)
            return
        }
        val old = files[rel]
        if (!expectedEtag.isNullOrEmpty() && old != null && old.etag != expectedEtag) {
            throw WebDavException(WebDavErrorKind.CONFLICT, "etag changed", 412)
        }
        files.remove(rel)
    }

    override suspend fun move(from: String, to: String, isDir: Boolean) {
        if (isDir) error("directory moves are not used by these tests")
        if (files.containsKey(to)) throw WebDavException(WebDavErrorKind.CONFLICT, "destination exists", 412)
        val file = files.remove(from)
            ?: throw WebDavException(WebDavErrorKind.NOT_FOUND, "missing $from", 404)
        files[to] = file
        addParents(to)
        moves.add(from to to)
    }

    private fun addParents(rel: String) {
        var parent = SyncPaths.parentRel(rel)
        while (parent.isNotEmpty()) {
            dirs.add(parent)
            parent = SyncPaths.parentRel(parent)
        }
    }
}
