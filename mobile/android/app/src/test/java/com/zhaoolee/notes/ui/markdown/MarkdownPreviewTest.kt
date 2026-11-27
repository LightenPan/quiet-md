package com.zhaoolee.notes.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreviewTest {
    @Test
    fun parses_common_markdown_blocks() {
        val markdown = buildString {
            appendLine("# Heading")
            appendLine("Paragraph with **bold** and [link](https://example.com).")
            appendLine("- [x] done")
            appendLine("- [ ] todo")
            appendLine("1. first")
            appendLine("> quote")
            appendLine("```kotlin")
            appendLine("println(\"hi\")")
            appendLine("```")
            appendLine("---")
        }

        val blocks = parseMarkdownBlocks(markdown)

        assertEquals(MarkdownBlock.Heading(1, "Heading"), blocks[0])
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertEquals(MarkdownBlock.Bullet("done", true, 0), blocks[2])
        assertEquals(MarkdownBlock.Bullet("todo", false, 0), blocks[3])
        assertEquals(MarkdownBlock.Numbered(1, "first", 0), blocks[4])
        assertEquals(MarkdownBlock.Quote("quote"), blocks[5])
        assertEquals(MarkdownBlock.Code("kotlin", "println(\"hi\")"), blocks[6])
        assertEquals(MarkdownBlock.Divider, blocks[7])
    }

    @Test
    fun keeps_nested_list_depth_and_groups_paragraph_lines() {
        val markdown = """
            - parent
              - child
            first line
            second line
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)

        assertEquals(MarkdownBlock.Bullet("parent", null, 0), blocks[0])
        assertEquals(MarkdownBlock.Bullet("child", null, 1), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("first line\nsecond line"), blocks[2])
    }

    @Test
    fun strips_closing_hashes_from_headings() {
        val blocks = parseMarkdownBlocks("## Title ##")

        assertEquals(MarkdownBlock.Heading(2, "Title"), blocks.single())
    }
}
