package com.fload.attribution.core

/**
 * A minimal JSON writer for the two request bodies: objects, strings,
 * booleans and whole numbers. A null value leaves its key out.
 */
internal object Json {
    fun write(value: Map<String, Any?>): String = StringBuilder().also { appendObject(it, value) }.toString()

    private fun appendObject(out: StringBuilder, value: Map<String, Any?>) {
        out.append('{')
        var first = true
        for ((key, item) in value) {
            if (item == null) continue
            if (!first) out.append(',')
            first = false
            appendString(out, key)
            out.append(':')
            appendValue(out, item)
        }
        out.append('}')
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendValue(out: StringBuilder, value: Any) {
        when (value) {
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Map<*, *> -> appendObject(out, value as Map<String, Any?>)
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class.java.simpleName}")
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' || char == ' ' || char == ' ' -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        out.append('"')
    }
}
