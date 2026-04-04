package com.example.lightime.t9

import android.database.sqlite.SQLiteDatabase
import com.example.lightime.db.DictionaryDbHelper

class T9Engine(private val db: SQLiteDatabase) {

    private val suggestionCache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    // Prefix-trie behavior is simulated by prefix query over t9 digit strings; nodes are loaded lazily from SQLite.
    fun suggestions(digits: String, limit: Int = 3): List<String> {
        if (digits.isBlank()) return emptyList()
        val cacheKey = "$digits#$limit"
        suggestionCache[cacheKey]?.let { return it }

        val out = ArrayList<String>(limit)
        val sql = """
            SELECT word FROM (
                SELECT word, 2 AS boosted, 0 AS recent_score, 0 AS base_rank
                FROM custom_words WHERE t9 LIKE ?
                UNION ALL
                SELECT word, boosted, recent_score, 999999 AS base_rank
                FROM user_words WHERE t9 LIKE ?
                UNION ALL
                SELECT word, 0 AS boosted, 0 AS recent_score, base_rank
                FROM words WHERE t9 LIKE ?
            )
            ORDER BY boosted DESC, recent_score DESC, base_rank ASC
            LIMIT ?
        """.trimIndent()
        db.rawQuery(sql, arrayOf("$digits%", "$digits%", "$digits%", limit.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out.distinct().take(limit).also { suggestionCache[cacheKey] = it }
    }

    fun invalidateCache() {
        suggestionCache.clear()
    }

    fun digitsFor(word: String): String = DictionaryDbHelper.t9Of(word)

    companion object {
        private const val MAX_CACHE_SIZE = 96
    }
}
