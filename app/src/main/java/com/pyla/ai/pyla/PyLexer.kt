package com.pyla.ai.pyla

/**
 * Tokenizer for the small Python subset used by .pyla playstyle scripts.
 *
 * Handles significant indentation (INDENT/DEDENT), implicit line joining inside
 * brackets, backslash line continuation, comments, numbers, strings and f-strings.
 */

enum class TokType { NAME, NUMBER, STRING, FSTRING, OP, NEWLINE, INDENT, DEDENT, EOF }

class Tok(
    val type: TokType,
    val value: String,
    val line: Int,
    val payload: Any? = null,
)

/** A piece of an f-string: either a literal chunk or an embedded expression source. */
sealed class FPart {
    class Lit(val text: String) : FPart()
    class Expr(val source: String, val conversion: String?) : FPart()
}

class PyLexException(message: String, val line: Int) : RuntimeException("Line $line: $message")

class PyLexer(private val src: String) {

    private var pos = 0
    private var line = 1
    private val tokens = ArrayList<Tok>()
    private val indentStack = ArrayDeque<Int>().apply { addLast(0) }
    private var parenDepth = 0
    private var lineStart = true

    companion object {
        private val THREE_CHAR_OPS = listOf("**=", "//=", "...", ">>=", "<<=")
        private val TWO_CHAR_OPS = listOf(
            "**", "//", "<=", ">=", "==", "!=", "->", "+=", "-=", "*=", "/=",
            "%=", "&=", "|=", "^=", ">>", "<<",
        )
        private const val SINGLE_OPS = "+-*/%<>=(){}[],:.&|^~@;"
    }

    fun tokenize(): List<Tok> {
        while (pos < src.length) {
            if (parenDepth == 0 && lineStart) {
                handleIndentation()
                lineStart = false
                if (pos >= src.length) break
            } else {
                lexOneToken()
            }
        }
        // flush trailing newline
        if (tokens.isNotEmpty() && tokens.last().type != TokType.NEWLINE &&
            tokens.last().type != TokType.INDENT && tokens.last().type != TokType.DEDENT) {
            tokens.add(Tok(TokType.NEWLINE, "\n", line))
        }
        while (indentStack.last() > 0) {
            indentStack.removeLast()
            tokens.add(Tok(TokType.DEDENT, "", line))
        }
        tokens.add(Tok(TokType.EOF, "", line))
        return tokens
    }

    private fun handleIndentation() {
        // Measure indentation; skip fully blank / comment-only lines.
        while (pos < src.length) {
            val startLine = line
            var indent = 0
            var i = pos
            while (i < src.length) {
                val c = src[i]
                when (c) {
                    ' ' -> { indent++; i++ }
                    '\t' -> { indent += 8 - (indent % 8); i++ }
                    else -> break
                }
            }
            if (i >= src.length) { pos = i; return }
            val c = src[i]
            if (c == '\n') { pos = i + 1; line++; continue }
            if (c == '\r') { pos = i + 1; continue }
            if (c == '#') {
                // comment-only line, skip to end
                while (i < src.length && src[i] != '\n') i++
                pos = i
                continue
            }
            // real content line
            pos = i
            val top = indentStack.last()
            if (indent > top) {
                indentStack.addLast(indent)
                tokens.add(Tok(TokType.INDENT, "", startLine))
            } else if (indent < top) {
                while (indentStack.last() > indent) {
                    indentStack.removeLast()
                    tokens.add(Tok(TokType.DEDENT, "", startLine))
                }
                if (indentStack.last() != indent) {
                    // Unindent doesn't match; align to nearest by pushing (lenient).
                    indentStack.addLast(indent)
                    tokens.add(Tok(TokType.INDENT, "", startLine))
                }
            }
            return
        }
    }

    private fun lexOneToken() {
        // skip inline whitespace
        while (pos < src.length && (src[pos] == ' ' || src[pos] == '\t' || src[pos] == '\r')) pos++
        if (pos >= src.length) return
        val c = src[pos]

        if (c == '#') {
            while (pos < src.length && src[pos] != '\n') pos++
            return
        }
        if (c == '\\' && pos + 1 < src.length && (src[pos + 1] == '\n' || src[pos + 1] == '\r')) {
            pos++
            if (pos < src.length && src[pos] == '\r') pos++
            if (pos < src.length && src[pos] == '\n') { pos++; line++ }
            return
        }
        if (c == '\n') {
            pos++
            line++
            if (parenDepth == 0) {
                lineStart = true
                val last = tokens.lastOrNull()
                if (last == null || last.type == TokType.NEWLINE ||
                    last.type == TokType.INDENT || last.type == TokType.DEDENT) return
                tokens.add(Tok(TokType.NEWLINE, "\n", line - 1))
            }
            return
        }
        if ((c == 'f' || c == 'F') && pos + 1 < src.length && (src[pos + 1] == '"' || src[pos + 1] == '\'')) {
            lexFString()
            return
        }
        if (c == '"' || c == '\'') {
            lexString()
            return
        }
        if (c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit())) {
            lexNumber()
            return
        }
        if (c.isLetter() || c == '_') {
            lexName()
            return
        }
        lexOperator()
    }

    private fun lexName() {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
        tokens.add(Tok(TokType.NAME, src.substring(start, pos), line))
    }

    private fun lexNumber() {
        val start = pos
        var isFloat = false
        while (pos < src.length && src[pos].isDigit()) pos++
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        val text = src.substring(start, pos)
        val payload: Any = if (isFloat) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        tokens.add(Tok(TokType.NUMBER, text, line, payload))
    }

    private fun readQuoted(): String {
        val quote = src[pos]
        pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos]
            if (c == '\\') {
                pos++
                if (pos >= src.length) break
                when (val e = src[pos]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '0' -> sb.append('\u0000')
                    else -> { sb.append('\\'); sb.append(e) }
                }
                pos++
            } else if (c == quote) {
                pos++
                return sb.toString()
            } else if (c == '\n') {
                throw PyLexException("unterminated string", line)
            } else {
                sb.append(c)
                pos++
            }
        }
        throw PyLexException("unterminated string", line)
    }

    private fun lexString() {
        val s = readQuoted()
        tokens.add(Tok(TokType.STRING, s, line, s))
    }

    private fun lexFString() {
        pos++ // skip f/F
        val quote = src[pos]
        pos++
        val parts = ArrayList<FPart>()
        val lit = StringBuilder()
        while (pos < src.length) {
            val c = src[pos]
            if (c == '\\') {
                pos++
                if (pos >= src.length) break
                when (val e = src[pos]) {
                    'n' -> lit.append('\n'); 't' -> lit.append('\t'); 'r' -> lit.append('\r')
                    '\\' -> lit.append('\\'); '\'' -> lit.append('\''); '"' -> lit.append('"')
                    else -> { lit.append('\\'); lit.append(e) }
                }
                pos++
            } else if (c == quote) {
                pos++
                if (lit.isNotEmpty()) parts.add(FPart.Lit(lit.toString()))
                tokens.add(Tok(TokType.FSTRING, "", line, parts))
                return
            } else if (c == '{') {
                if (pos + 1 < src.length && src[pos + 1] == '{') { lit.append('{'); pos += 2; continue }
                if (lit.isNotEmpty()) { parts.add(FPart.Lit(lit.toString())); lit.clear() }
                pos++
                val exprSb = StringBuilder()
                var depth = 1
                var conversion: String? = null
                while (pos < src.length && depth > 0) {
                    val ch = src[pos]
                    if (ch == '{') { depth++; exprSb.append(ch); pos++ }
                    else if (ch == '}') { depth--; if (depth == 0) { pos++; break }; exprSb.append(ch); pos++ }
                    else if ((ch == ':' || ch == '!') && depth == 1) {
                        // format spec / conversion -> consume rest until closing brace, ignore for logic
                        conversion = ""
                        pos++
                        var d2 = 1
                        while (pos < src.length && d2 > 0) {
                            val cc = src[pos]
                            if (cc == '{') d2++
                            else if (cc == '}') { d2--; if (d2 == 0) { pos++; break } }
                            pos++
                        }
                        break
                    } else { exprSb.append(ch); pos++ }
                }
                parts.add(FPart.Expr(exprSb.toString().trim(), conversion))
            } else if (c == '}') {
                if (pos + 1 < src.length && src[pos + 1] == '}') { lit.append('}'); pos += 2; continue }
                pos++
            } else if (c == '\n') {
                throw PyLexException("unterminated f-string", line)
            } else {
                lit.append(c)
                pos++
            }
        }
        throw PyLexException("unterminated f-string", line)
    }

    private fun lexOperator() {
        if (pos + 3 <= src.length) {
            val three = src.substring(pos, pos + 3)
            if (three in THREE_CHAR_OPS) {
                tokens.add(Tok(TokType.OP, three, line)); pos += 3; return
            }
        }
        if (pos + 2 <= src.length) {
            val two = src.substring(pos, pos + 2)
            if (two in TWO_CHAR_OPS) {
                tokens.add(Tok(TokType.OP, two, line)); pos += 2; return
            }
        }
        val c = src[pos]
        when (c) {
            '(', '[', '{' -> parenDepth++
            ')', ']', '}' -> if (parenDepth > 0) parenDepth--
        }
        if (SINGLE_OPS.indexOf(c) >= 0) {
            tokens.add(Tok(TokType.OP, c.toString(), line)); pos++; return
        }
        throw PyLexException("unexpected character '$c'", line)
    }
}
