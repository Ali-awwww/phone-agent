package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testMarkdownConversion() {
        val raw = "### 3. نصيحة\n- **عرض اللعبة** المميز"
        val annotated = markdownToAnnotatedString(raw)
        val resultString = annotated.text
        assertFalse("Should not contain raw markdown ###", resultString.contains("###"))
        assertFalse("Should not contain raw markdown **", resultString.contains("**"))
        assertEquals(true, resultString.contains("3. نصيحة"))
        assertEquals(true, resultString.contains("عرض اللعبة"))
    }
}
