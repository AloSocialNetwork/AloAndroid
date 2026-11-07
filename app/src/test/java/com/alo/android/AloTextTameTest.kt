package com.alo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AloTextTameTest {
    private fun marks(text: String) = text.count { Character.getType(it).toByte() == Character.NON_SPACING_MARK }

    @Test
    fun aTowerOfMarksIsCut() {
        val tamed = aloTameText("Т" + "\u0301".repeat(40) + "ест")
        assertEquals(3, marks(tamed))
        assertEquals("ест", tamed.takeLast(3))
    }

    @Test
    fun directionOverridesAreDropped() {
        assertFalse(aloTameText("имя\u202Eтекст").contains('\u202E'))
    }

    @Test
    fun wideLigaturesAreCapped() {
        assertEquals("GG " + "\uFDFD".repeat(3), aloTameText("GG " + "\uFDFD".repeat(50)))
        assertEquals("\u0643\u0640\u0640\u0644", aloTameText("\u0643" + "\u0640".repeat(9) + "\u0644"))
    }

    @Test
    fun ordinaryTextAndEmojiStay() {
        val text = "Привет, Việt नमस्ते 👨‍👩‍👧 ❤️ 1️⃣"
        assertEquals(text, aloTameText(text))
    }
}
