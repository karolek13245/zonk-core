package com.example.aiapp

// Splits a reply into text and code blocks. No Android classes in here, so it can be tested on a plain computer.

/** A reply is split into normal text and code blocks (the parts between ``` fences). */
sealed interface Seg {
    data class Text(val text: String) : Seg
    data class Code(val lang: String, val code: String, val closed: Boolean) : Seg
}

fun parseSegments(src: String): List<Seg> {
    val out = ArrayList<Seg>()
    fun addText(raw: String) {
        val t = raw.trim('\n')
        if (t.isNotBlank()) out.add(Seg.Text(t))
    }
    var i = 0
    while (i < src.length) {
        val open = src.indexOf("```", i)
        if (open < 0) {
            addText(src.substring(i))
            break
        }
        addText(src.substring(i, open))
        val lineEnd = src.indexOf('\n', open + 3)
        // A small model sometimes writes a whole block on one line: ```print(1)```
        val sameLine = src.indexOf("```", open + 3)
        if (sameLine >= 0 && (lineEnd < 0 || sameLine < lineEnd)) {
            out.add(Seg.Code("", src.substring(open + 3, sameLine).trim(), true))
            i = sameLine + 3
            continue
        }
        if (lineEnd < 0) {
            // The reply is still being written and the fence line isn't finished yet.
            out.add(Seg.Code(src.substring(open + 3).trim(), "", false))
            break
        }
        val lang = src.substring(open + 3, lineEnd).trim()
        val close = src.indexOf("```", lineEnd + 1)
        if (close < 0) {
            out.add(Seg.Code(lang, src.substring(lineEnd + 1).trimEnd('\n'), false))
            break
        }
        out.add(Seg.Code(lang, src.substring(lineEnd + 1, close).trimEnd('\n'), true))
        i = close + 3
    }
    return out
}
