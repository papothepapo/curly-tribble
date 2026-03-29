package com.example.lightime.t9

import android.database.sqlite.SQLiteDatabase
import com.example.lightime.db.DictionaryDbHelper

class T9Engine(private val db: SQLiteDatabase) {

    // Prefix-trie behavior is simulated by prefix query over t9 digit strings; nodes are loaded lazily from SQLite.
    fun suggestions(digits: String, limit: Int = 3): List<String> {
        if (digits.isBlank()) return emptyList()
        val out = ArrayList<String>(limit)
        val sql = """
            SELECT word FROM (
                SELECT word, boosted, recent_score, 999999 AS base_rank
                FROM user_words WHERE t9 LIKE ?
                UNION ALL
                SELECT word, 0 AS boosted, 0 AS recent_score, base_rank
                FROM words WHERE t9 LIKE ?
            )
            ORDER BY boosted DESC, recent_score DESC, base_rank ASC
            LIMIT ?
        """.trimIndent()
        db.rawQuery(sql, arrayOf("$digits%", "$digits%", limit.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out.distinct().take(limit)
    }

    fun digitsFor(word: String): String = DictionaryDbHelper.t9Of(word)
}
