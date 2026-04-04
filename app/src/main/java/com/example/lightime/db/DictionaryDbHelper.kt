package com.example.lightime.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedReader
import java.io.InputStreamReader

class DictionaryDbHelper(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS words(
                word TEXT PRIMARY KEY,
                t9 TEXT NOT NULL,
                base_rank INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_words(
                word TEXT PRIMARY KEY,
                t9 TEXT NOT NULL,
                boosted INTEGER NOT NULL DEFAULT 0,
                recent_score INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS custom_words(
                word TEXT PRIMARY KEY,
                t9 TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_t9 ON words(t9)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_words_t9 ON user_words(t9)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custom_words_t9 ON custom_words(t9)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
        if (oldVersion < 5) {
            db.execSQL("DELETE FROM words")
            db.execSQL("DELETE FROM custom_words")
        }
    }

    fun ensureSeedLoaded() {
        val db = writableDatabase
        onCreate(db)
        db.rawQuery("SELECT COUNT(1) FROM words", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) > 0) return
        }

        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR REPLACE INTO words(word,t9,base_rank) VALUES(?,?,?)")
            val nextRank = loadRankedSeed(stmt)
            loadPlainSeed(stmt, nextRank)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertUserWord(word: String, boosted: Boolean = false) {
        val normalized = normalizeWord(word)
        if (normalized.isBlank()) return
        val t9 = t9Of(normalized)
        if (t9.isBlank()) return
        val now = System.currentTimeMillis()
        val existingScore = readableDatabase.rawQuery("SELECT recent_score FROM user_words WHERE word=?", arrayOf(normalized)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

        val values = ContentValues().apply {
            put("word", normalized)
            put("t9", t9)
            put("boosted", if (boosted) 1 else 0)
            put("recent_score", existingScore + 1)
            put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict("user_words", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun replaceCustomWords(words: Collection<String>) {
        val db = writableDatabase
        val normalizedWords = words.asSequence()
            .map(::normalizeWord)
            .filter { it.isNotBlank() && t9Of(it).isNotBlank() }
            .distinct()
            .toList()

        db.beginTransaction()
        try {
            db.delete("custom_words", null, null)
            val stmt = db.compileStatement("INSERT OR REPLACE INTO custom_words(word,t9) VALUES(?,?)")
            normalizedWords.forEach { word ->
                stmt.clearBindings()
                stmt.bindString(1, word)
                stmt.bindString(2, t9Of(word))
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadRankedSeed(stmt: android.database.sqlite.SQLiteStatement): Int {
        var maxRank = 0
        BufferedReader(InputStreamReader(context.assets.open("base_words.tsv"))).useLines { lines ->
            lines.forEach { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    stmt.clearBindings()
                    stmt.bindString(1, p[0])
                    stmt.bindString(2, p[1])
                    val rank = p[2].toIntOrNull() ?: return@forEach
                    stmt.bindLong(3, rank.toLong())
                    stmt.executeInsert()
                    if (rank > maxRank) maxRank = rank
                }
            }
        }
        return maxRank + 1
    }

    private fun loadPlainSeed(stmt: android.database.sqlite.SQLiteStatement, startRank: Int) {
        var rank = startRank
        BufferedReader(InputStreamReader(context.assets.open("extended_words.txt"))).useLines { lines ->
            lines.forEach { rawWord ->
                val word = normalizeWord(rawWord)
                val t9 = t9Of(word)
                if (word.isBlank() || t9.isBlank()) return@forEach
                stmt.clearBindings()
                stmt.bindString(1, word)
                stmt.bindString(2, t9)
                stmt.bindLong(3, rank.toLong())
                stmt.executeInsert()
                rank += 1
            }
        }
    }

    companion object {
        const val DB_NAME = "dictionary.db"
        private const val DB_VERSION = 5

        fun normalizeWord(word: String): String {
            return word
                .trim()
                .lowercase()
                .replace(Regex("^[^a-z']+|[^a-z']+$"), "")
                .replace(Regex("[^a-z']"), "")
                .trim('\'')
        }

        fun t9Of(word: String): String {
            val map = mapOf(
                'a' to '2', 'b' to '2', 'c' to '2',
                'd' to '3', 'e' to '3', 'f' to '3',
                'g' to '4', 'h' to '4', 'i' to '4',
                'j' to '5', 'k' to '5', 'l' to '5',
                'm' to '6', 'n' to '6', 'o' to '6',
                'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
                't' to '8', 'u' to '8', 'v' to '8',
                'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
            )
            val sb = StringBuilder(word.length)
            word.lowercase().forEach { ch -> map[ch]?.let(sb::append) }
            return sb.toString()
        }
    }
}
