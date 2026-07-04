package com.pyla.ai.config

internal object TomlParser {

    fun parse(text: String): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        var currentTable: MutableMap<String, Any?> = root
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = stripComment(raw).trim()
            if (line.isEmpty()) { i++; continue }

            if (line.startsWith('[') && line.endsWith(']')) {
                val name = line.substring(1, line.length - 1).trim()
                currentTable = navigateTable(root, name)
                i++; continue
            }

            val eq = line.indexOf('=')
            if (eq < 0) { i++; continue }
            val key = line.substring(0, eq).trim()
            var valuePart = line.substring(eq + 1).trim()

            if (valuePart.startsWith('[') && !valuePart.contains(']')) {
                val sb = StringBuilder(valuePart)
                var depth = sb.count { it == '[' } - sb.count { it == ']' }
                while (depth > 0 && i + 1 < lines.size) {
                    i++
                    sb.append('\n').append(stripComment(lines[i]))
                    depth = sb.count { it == '[' } - sb.count { it == ']' }
                }
                valuePart = sb.toString()
            }

            currentTable[key] = parseValue(valuePart)
            i++
        }
        return root
    }

    private fun navigateTable(root: MutableMap<String, Any?>, dotted: String): MutableMap<String, Any?> {
        val parts = dotted.split('.')
        var cur: MutableMap<String, Any?> = root
        for (p in parts) {
            val existing = cur[p]
            if (existing is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                cur = existing as MutableMap<String, Any?>
            } else {
                val child = LinkedHashMap<String, Any?>()
                cur[p] = child
                cur = child
            }
        }
        return cur
    }

    private fun stripComment(line: String): String {
        val sb = StringBuilder(line.length)
        var inString = false
        for (c in line) {
            if (c == '"') inString = !inString
            if (c == '#' && !inString) break
            sb.append(c)
        }
        return sb.toString()
    }

    private fun parseValue(raw: String): Any? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.startsWith('[')) return parseArray(v)
        if (v.startsWith('{')) return parseInlineObject(v)
        if (v.startsWith('"') && v.endsWith('"')) return v.substring(1, v.length - 1)
        if (v == "true") return true
        if (v == "false") return false
        v.toIntOrNull()?.let { return it }
        v.toDoubleOrNull()?.let { return it }
        return v
    }

    private fun parseArray(raw: String): List<Any?> {
        val inner = raw.substring(1, raw.length - 1)
        val parts = splitTopLevel(inner, ',')
        return parts.map { parseValue(it) }.filter { it != null }
    }

    private fun parseInlineObject(raw: String): Map<String, Any?> {
        val inner = raw.substring(1, raw.length - 1)
        val out = LinkedHashMap<String, Any?>()
        for (part in splitTopLevel(inner, ',')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            out[part.substring(0, eq).trim().trim('"')] = parseValue(part.substring(eq + 1).trim())
        }
        return out
    }

    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var lastWasToken = false
        for (c in s) {
            if (c == '"') inString = !inString
            if (!inString) {
                if (c == '[' || c == '{') depth++
                if (c == ']' || c == '}') depth--
                if (c == sep && depth == 0) {
                    val t = sb.toString().trim()
                    if (t.isNotEmpty()) out.add(t)
                    sb.setLength(0); lastWasToken = true; continue
                }
            }
            sb.append(c); lastWasToken = false
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }
}