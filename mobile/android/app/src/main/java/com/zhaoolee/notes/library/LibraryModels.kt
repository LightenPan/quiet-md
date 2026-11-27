package com.zhaoolee.notes.library

import java.io.IOException

data class LibraryNote(
    val relativePath: String,
    val title: String,
    val body: String,
    val folder: String,
    val modifiedAt: Long,
    val createdAt: Long,
    val pinned: Boolean,
    val favorite: Boolean,
    val editable: Boolean,
    val contentHash: String = ""
)

data class LibraryWarning(val relativePath: String, val message: String)

data class LibrarySnapshot(
    val notes: List<LibraryNote>,
    val warnings: List<LibraryWarning> = emptyList()
)

class NoteConflictException(val relativePath: String, message: String) : IOException(message)

class NoteNotFoundException(val relativePath: String) : IOException("便签不存在：$relativePath")

class NoteTooLargeException(val relativePath: String, val size: Long) :
    IOException("便签超过 100 MiB：$relativePath（$size 字节）")
