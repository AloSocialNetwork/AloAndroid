package com.alo.android

import java.text.BreakIterator

/**
 * Display-side twin of the server's text-guard (web/js/text-tame.js on the site). The server cleans everything it can
 * read, but it never sees the text of encrypted messages: at most 3 combining marks on one visible character, and no
 * direction overrides. Counting per grapheme matters: «letter + ZWNJ + 3 marks + ZWNJ + 3 marks…» stacks into one tower.
 * Emoji keep their variation selectors and keycaps; ordinary text in any language is left as it is.
 */
internal fun aloTameText(text: String, maxMarks: Int = 3): String {
    if (text.isEmpty() || text.none { aloMayNeedTaming(it) }) return text
    return aloLimitWide(aloTameMarks(text, maxMarks))
}

// One-code-point ligatures that draw a whole phrase (﷽ …): a few are fine, fifty build a tower. Long runs of tatweel stretch words.
private fun aloIsWide(char: Char): Boolean = char in '\uFDF0'..'\uFDFB' || char == '\uFDFD'

private fun aloLimitWide(text: String, maxWide: Int = 3): String {
    if (text.none { aloIsWide(it) || it == '\u0640' }) return text
    val out = StringBuilder(text.length)
    var wide = 0
    var tatweel = 0
    for (char in text) {
        tatweel = if (char == '\u0640') tatweel + 1 else 0
        if (tatweel > 2) continue
        if (aloIsWide(char) && ++wide > maxWide) continue
        out.append(char)
    }
    return out.toString()
}

private fun aloTameMarks(text: String, maxMarks: Int): String {
    val clean = text.filterNot(::aloIsBidiControl)
    val graphemes = BreakIterator.getCharacterInstance()
    graphemes.setText(clean)
    val out = StringBuilder(clean.length)
    var start = graphemes.first()
    var end = graphemes.next()
    while (end != BreakIterator.DONE) {
        out.append(aloTameGrapheme(clean.substring(start, end), maxMarks))
        start = end
        end = graphemes.next()
    }
    return out.toString()
}

private fun aloTameGrapheme(grapheme: String, maxMarks: Int): String {
    val points = grapheme.codePoints().toArray()
    if (points.count(::aloCountsAsMark) <= maxMarks) return grapheme
    var kept = 0
    val out = StringBuilder()
    for (point in points) {
        when {
            aloCountsAsMark(point) -> if (kept < maxMarks) {
                out.appendCodePoint(point)
                kept += 1
            }
            point != 0x200C && point != 0x200D -> out.appendCodePoint(point)
        }
    }
    return out.toString()
}

private fun aloCountsAsMark(point: Int): Boolean {
    val emojiPart = point in 0xFE00..0xFE0F || point == 0x20E3 || point in 0xE0100..0xE01EF
    if (emojiPart) return false
    return when (Character.getType(point).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }
}

private fun aloIsBidiControl(char: Char): Boolean = char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069'

private fun aloMayNeedTaming(char: Char): Boolean = aloIsBidiControl(char) || aloIsWide(char) || char == '\u0640' || char == '\u200C' || char == '\u200D' ||
    when (Character.getType(char).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK, Character.SURROGATE -> true
        else -> false
    }
