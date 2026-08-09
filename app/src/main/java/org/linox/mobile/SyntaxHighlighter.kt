package org.linox.mobile

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

/**
 * LinOx v0.9 syntax highlighter.
 *
 * Small, dependency-free, regex-based highlighter for the built-in editor.
 * It is not a full tokenizer/parser — good enough for readability while
 * editing Python/Bash/C-like/JS files on a phone, without pulling in a
 * heavyweight highlighting library.
 */
object SyntaxHighlighter {
    private val KEYWORD_COLOR = Color.rgb(199, 146, 234)
    private val STRING_COLOR = Color.rgb(173, 219, 103)
    private val COMMENT_COLOR = Color.rgb(120, 130, 140)
    private val NUMBER_COLOR = Color.rgb(247, 140, 108)

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as",
        "with", "try", "except", "finally", "raise", "pass", "break", "continue", "in", "is",
        "not", "and", "or", "lambda", "yield", "global", "nonlocal", "None", "True", "False", "self", "async", "await"
    )
    private val C_LIKE_KEYWORDS = setOf(
        "int", "char", "long", "short", "float", "double", "void", "struct", "typedef", "static",
        "const", "unsigned", "signed", "return", "if", "else", "for", "while", "do", "switch",
        "case", "break", "continue", "sizeof", "include", "define", "class", "public", "private",
        "protected", "namespace", "new", "delete", "template", "virtual", "override", "using"
    )
    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "case", "esac", "in",
        "function", "return", "exit", "echo", "export", "local", "shift", "set", "trap"
    )
    private val JS_KEYWORDS = setOf(
        "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "const", "let", "var", "new", "class", "extends", "import", "export",
        "default", "async", "await", "try", "catch", "finally", "throw", "typeof", "instanceof", "this"
    )

    private val NUMBER_RE = Regex("\\b\\d+(\\.\\d+)?\\b")
    private val DQUOTE_RE = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
    private val SQUOTE_RE = Regex("'(?:\\\\.|[^'\\\\])*'")

    enum class Lang { PYTHON, C_LIKE, SHELL, JS, NONE }

    fun langForFile(name: String): Lang = when (name.substringAfterLast('.', "").lowercase()) {
        "py" -> Lang.PYTHON
        "c", "h", "cpp", "hpp", "cc", "cxx", "java", "kt" -> Lang.C_LIKE
        "sh", "bash" -> Lang.SHELL
        "js", "ts", "jsx", "tsx" -> Lang.JS
        else -> Lang.NONE
    }

    private fun lineCommentPrefix(lang: Lang): String? = when (lang) {
        Lang.PYTHON, Lang.SHELL -> "#"
        Lang.C_LIKE, Lang.JS -> "//"
        Lang.NONE -> null
    }

    private fun keywords(lang: Lang): Set<String> = when (lang) {
        Lang.PYTHON -> PYTHON_KEYWORDS
        Lang.C_LIKE -> C_LIKE_KEYWORDS
        Lang.SHELL -> SHELL_KEYWORDS
        Lang.JS -> JS_KEYWORDS
        Lang.NONE -> emptySet()
    }

    /** Applies highlighting spans in place. Caller must strip old spans first. */
    fun highlight(text: Editable, lang: Lang) {
        if (lang == Lang.NONE) return
        val keywordRe = Regex("\\b(${keywords(lang).joinToString("|")})\\b")
        val commentPrefix = lineCommentPrefix(lang)

        // Numbers
        NUMBER_RE.findAll(text).forEach { m -> span(text, m.range, ForegroundColorSpan(NUMBER_COLOR)) }
        // Keywords
        keywordRe.findAll(text).forEach { m -> span(text, m.range, ForegroundColorSpan(KEYWORD_COLOR)); span(text, m.range, StyleSpan(Typeface.BOLD)) }
        // Strings (after keywords/numbers so they visually win when overlapping is impossible anyway)
        DQUOTE_RE.findAll(text).forEach { m -> span(text, m.range, ForegroundColorSpan(STRING_COLOR)) }
        SQUOTE_RE.findAll(text).forEach { m -> span(text, m.range, ForegroundColorSpan(STRING_COLOR)) }
        // Line comments
        if (commentPrefix != null) {
            var idx = 0
            val str = text.toString()
            while (idx < str.length) {
                val nl = str.indexOf('\n', idx).let { if (it == -1) str.length else it }
                val line = str.substring(idx, nl)
                val cIdx = line.indexOf(commentPrefix)
                if (cIdx >= 0) span(text, (idx + cIdx) until nl, ForegroundColorSpan(COMMENT_COLOR))
                idx = nl + 1
            }
        }
    }

    private fun span(text: Editable, range: IntRange, what: Any) {
        if (range.isEmpty() && range.first >= range.last) return
        val end = (range.last + 1).coerceAtMost(text.length)
        val start = range.first.coerceAtMost(end)
        if (start >= end) return
        text.setSpan(what, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun clear(text: Editable) {
        val fgSpans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
        fgSpans.forEach { text.removeSpan(it) }
        val styleSpans = text.getSpans(0, text.length, StyleSpan::class.java)
        styleSpans.forEach { text.removeSpan(it) }
    }
}
