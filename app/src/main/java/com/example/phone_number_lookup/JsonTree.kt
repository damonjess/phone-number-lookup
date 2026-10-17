package com.example.phone_number_lookup

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses JSON into plain Kotlin values (Map / List / String / Number / Boolean / null) so the
 * response-handling code can be tested without org.json.
 *
 * Text that isn't JSON (for example an HTML error page) comes back as a plain String, never as a
 * Map or List, so callers can tell the two apart.
 */
internal object JsonTree {
    fun parse(text: String): Any? = try {
        convert(JSONTokener(text).nextValue())
    } catch (_: Exception) {
        null
    }

    private fun convert(value: Any?): Any? = when {
        value == null || value == JSONObject.NULL -> null
        value is JSONObject -> value.keys().asSequence().associateWith { convert(value.opt(it)) }
        value is JSONArray -> (0 until value.length()).map { convert(value.opt(it)) }
        else -> value
    }
}
