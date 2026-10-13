package com.example.phone_number_lookup

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Local-only history. Nothing is uploaded and no contacts or call-log permissions are used. */
data class HistoryEntry(
    val id: Long,
    val number: String,
    val label: String,
    val note: String,
    val status: String,
    val createdAt: Long
)

class HistoryStore(context: Context) {
    // Local JVM tests do not provide Android's SharedPreferences implementation. Keep a
    // process-local fallback there; on a real device this always uses private app storage.
    private val preferences: SharedPreferences? = runCatching {
        context.getSharedPreferences("number_finder_history", Context.MODE_PRIVATE)
    }.getOrNull()
    private var inMemoryEntries: List<HistoryEntry> = emptyList()
    private val key = "entries"

    fun load(): List<HistoryEntry> = runCatching {
        val stored = preferences?.getString(key, null)
        if (stored == null && preferences == null) return@runCatching inMemoryEntries
        val array = JSONArray(stored ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            HistoryEntry(
                id = item.optLong("id"),
                number = item.optString("number"),
                label = item.optString("label"),
                note = item.optString("note"),
                status = item.optString("status", "Unknown"),
                createdAt = item.optLong("createdAt")
            )
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun add(number: String, label: String, note: String): List<HistoryEntry> {
        val now = System.currentTimeMillis()
        val updated = (listOf(HistoryEntry(now, number, label, note.trim(), "Unknown", now)) + load())
            .take(MAX_ENTRIES)
        save(updated)
        return updated
    }

    fun update(id: Long, note: String? = null, status: String? = null): List<HistoryEntry> {
        val updated = load().map { entry ->
            if (entry.id != id) entry else entry.copy(
                note = note ?: entry.note,
                status = status ?: entry.status
            )
        }
        save(updated)
        return updated
    }

    fun delete(id: Long): List<HistoryEntry> {
        val updated = load().filterNot { it.id == id }
        save(updated)
        return updated
    }

    fun clear() {
        inMemoryEntries = emptyList()
        preferences?.edit()?.remove(key)?.apply()
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("number", entry.number)
                put("label", entry.label)
                put("note", entry.note)
                put("status", entry.status)
                put("createdAt", entry.createdAt)
            })
        }
        inMemoryEntries = entries
        preferences?.edit()?.putString(key, array.toString())?.apply()
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
