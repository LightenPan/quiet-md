package com.zhaoolee.notes.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal object AtomicFiles {
    const val MAX_BYTES = 100L * 1024 * 1024

    fun readBytes(file: File): ByteArray {
        val size = file.length()
        if (size > MAX_BYTES) throw NoteTooLargeException(file.name, size)
        val bytes = file.readBytes()
        if (bytes.size > MAX_BYTES) throw NoteTooLargeException(file.name, bytes.size.toLong())
        return bytes
    }

    fun readText(file: File): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(readBytes(file))).toString()
    }

    fun writeText(file: File, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw NoteTooLargeException(file.name, bytes.size.toLong())
        writeBytes(file, bytes)
    }

    fun writeBytes(file: File, bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) throw NoteTooLargeException(file.name, bytes.size.toLong())
        val parent = file.parentFile ?: throw IOException("没有父目录：${file.path}")
        parent.mkdirs()
        val tmp = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            move(tmp, file, replace = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun move(from: File, to: File, replace: Boolean = false) {
        to.parentFile?.mkdirs()
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(from.toPath(), to.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback = if (replace) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }
            Files.move(from.toPath(), to.toPath(), *fallback)
        }
    }

    fun copy(from: File, to: File) {
        val parent = to.parentFile ?: throw IOException("没有父目录：${to.path}")
        parent.mkdirs()
        val tmp = File(parent, ".${to.name}.${UUID.randomUUID()}.tmp")
        try {
            from.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                    out.fd.sync()
                }
            }
            move(tmp, to, replace = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String {
        if (file.length() > MAX_BYTES) throw NoteTooLargeException(file.name, file.length())
        return sha256Unlimited(file)
    }

    fun sha256Unlimited(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
