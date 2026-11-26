package com.example.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

fun markdownToAnnotatedString(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                        append(trimmed.removePrefix("### ").trim())
                    }
                }
                trimmed.startsWith("## ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                        append(trimmed.removePrefix("## ").trim())
                    }
                }
                trimmed.startsWith("# ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                        append(trimmed.removePrefix("# ").trim())
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("• ") -> {
                    append("• ")
                    appendMarkdownInline(trimmed.removePrefix("- ").removePrefix("• ").trim())
                }
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val dotIdx = trimmed.indexOf('.')
                    if (dotIdx != -1) {
                        val numStr = trimmed.take(dotIdx + 1)
                        val rest = trimmed.drop(dotIdx + 1).trim()
                        append("$numStr ")
                        appendMarkdownInline(rest)
                    } else {
                        appendMarkdownInline(trimmed)
                    }
                }
                else -> {
                    appendMarkdownInline(trimmed)
                }
            }
            if (index < lines.lastIndex) {
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(text: String) {
    val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
    val matches = boldRegex.findAll(text).toList()
    if (matches.isEmpty()) {
        append(text)
        return
    }

    var lastIndex = 0
    for (match in matches) {
        val range = match.range
        if (range.first > lastIndex) {
            append(text.substring(lastIndex, range.first))
        }
        val innerText = match.groupValues[1]
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(innerText)
        }
        lastIndex = range.last + 1
    }
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val annotated = remember(text) { markdownToAnnotatedString(text) }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.bodyLarge
    )
}
