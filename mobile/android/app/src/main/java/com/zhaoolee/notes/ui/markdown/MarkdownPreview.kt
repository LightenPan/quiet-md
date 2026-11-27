package com.zhaoolee.notes.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    if (blocks.isEmpty()) {
        Text(
            "空白便签",
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MarkdownBlock.Paragraph -> Text(
                    inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MarkdownBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        inlineMarkdown(block.text),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontStyle = FontStyle.Italic,
                            lineHeight = 25.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is MarkdownBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                ) {
                    block.language?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        block.code,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MarkdownBlock.Bullet -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.depth * 16).dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        when (block.checked) {
                            true -> "☑"
                            false -> "☐"
                            null -> "•"
                        },
                        modifier = Modifier.width(22.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        inlineMarkdown(block.text),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MarkdownBlock.Numbered -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.depth * 16).dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "${block.number}.",
                        modifier = Modifier.width(28.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        inlineMarkdown(block.text),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                MarkdownBlock.Divider -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, primary, codeBackground) {
        buildAnnotatedString {
            appendMarkdownInline(text, SpanStyle(), primary, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    base: SpanStyle,
    primary: Color,
    codeBackground: Color
) {
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length) {
            append(text[i + 1])
            i += 2
            continue
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(base.merge(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground))) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (text.startsWith("![", i)) {
            val link = parseLink(text, i, true)
            if (link != null) {
                withStyle(base.merge(SpanStyle(color = primary))) {
                    append(if (link.label.isBlank()) "🖼" else "🖼 ${link.label}")
                }
                i = link.end
                continue
            }
        }
        if (text[i] == '[') {
            val link = parseLink(text, i, false)
            if (link != null) {
                val linkStyle = base.merge(
                    SpanStyle(color = primary, textDecoration = TextDecoration.Underline)
                )
                withStyle(linkStyle) {
                    appendMarkdownInline(link.label, linkStyle, primary, codeBackground)
                }
                i = link.end
                continue
            }
        }
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 2) {
                val style = base.merge(SpanStyle(fontWeight = FontWeight.Bold))
                withStyle(style) {
                    appendMarkdownInline(text.substring(i + 2, end), style, primary, codeBackground)
                }
                i = end + 2
                continue
            }
        }
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                val style = base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                withStyle(style) {
                    appendMarkdownInline(text.substring(i + 2, end), style, primary, codeBackground)
                }
                i = end + 2
                continue
            }
        }
        if (text[i] == '*' || text[i] == '_') {
            val marker = text[i]
            val end = text.indexOf(marker, i + 1)
            if (end > i + 1) {
                val style = base.merge(SpanStyle(fontStyle = FontStyle.Italic))
                withStyle(style) {
                    appendMarkdownInline(text.substring(i + 1, end), style, primary, codeBackground)
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

private data class ParsedLink(val label: String, val url: String, val end: Int)

private fun parseLink(text: String, start: Int, image: Boolean): ParsedLink? {
    val labelStart = start + if (image) 2 else 1
    val bracket = text.indexOf(']', labelStart)
    if (bracket < 0 || bracket + 1 >= text.length || text[bracket + 1] != '(') return null
    val close = text.indexOf(')', bracket + 2)
    if (close < 0) return null
    return ParsedLink(
        text.substring(labelStart, bracket),
        text.substring(bracket + 2, close).trim(),
        close + 1
    )
}

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data class Bullet(val text: String, val checked: Boolean?, val depth: Int) : MarkdownBlock
    data class Numbered(val number: Int, val text: String, val depth: Int) : MarkdownBlock
    data object Divider : MarkdownBlock
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        val fence = fenceLanguage(line)
        if (fence != null) {
            val language = fence.ifBlank { null }
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !isFence(lines[i])) {
                code.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks.add(MarkdownBlock.Code(language, code.joinToString("\n")))
            continue
        }
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            val text = heading.groupValues[2].replace(Regex("""\s+#+\s*$"""), "").trim()
            blocks.add(MarkdownBlock.Heading(heading.groupValues[1].length, text))
            i++
            continue
        }
        if (isDivider(line)) {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }
        if (line.trimStart().startsWith('>')) {
            val quote = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith('>')) {
                quote.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                i++
            }
            blocks.add(MarkdownBlock.Quote(quote.joinToString("\n")))
            continue
        }
        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            var text = bullet.groupValues[2]
            val checked = when {
                text.startsWith("[ ] ") -> false
                text.startsWith("[x] ") || text.startsWith("[X] ") -> true
                else -> null
            }
            if (checked != null) text = text.drop(4)
            blocks.add(MarkdownBlock.Bullet(text, checked, indentDepth(bullet.groupValues[1])))
            i++
            continue
        }
        val numbered = NUMBERED.matchEntire(line)
        if (numbered != null) {
            blocks.add(
                MarkdownBlock.Numbered(
                    numbered.groupValues[2].toIntOrNull() ?: 1,
                    numbered.groupValues[3],
                    indentDepth(numbered.groupValues[1])
                )
            )
            i++
            continue
        }
        val paragraph = mutableListOf<String>()
        while (i < lines.size) {
            val current = lines[i]
            if (current.isBlank() || isSpecialStart(current)) break
            paragraph.add(current.trimEnd())
            i++
        }
        if (paragraph.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n")))
        } else {
            i++
        }
    }
    return blocks
}

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")

private fun indentDepth(indent: String): Int = indent.replace("\t", "  ").length / 2

private fun isSpecialStart(line: String): Boolean =
    fenceLanguage(line) != null ||
        HEADING.matches(line) ||
        isDivider(line) ||
        line.trimStart().startsWith('>') ||
        BULLET.matches(line) ||
        NUMBERED.matches(line)

private fun fenceLanguage(line: String): String? {
    val trimmed = line.trimStart()
    return when {
        trimmed.startsWith("```") -> trimmed.removePrefix("```").trim()
        trimmed.startsWith("~~~") -> trimmed.removePrefix("~~~").trim()
        else -> null
    }
}

private fun isFence(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("```") || trimmed.startsWith("~~~")
}

private fun isDivider(line: String): Boolean {
    val compact = line.trim()
    if (compact.length < 3) return false
    val marker = compact.firstOrNull() ?: return false
    if (marker !in "-*_") return false
    return compact.all { it == marker || it == ' ' } && compact.count { it == marker } >= 3
}
