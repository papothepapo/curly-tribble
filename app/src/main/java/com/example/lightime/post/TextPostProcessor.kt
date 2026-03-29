package com.example.lightime.post

class TextPostProcessor(private val spellingNormalizer: SpellingNormalizer = SpellingNormalizer()) {
    fun process(
        raw: String,
        corrections: Map<String, String>,
        emojiMap: Map<String, String>,
        spellingMode: Int
    ): String {
        var text = spellingNormalizer.normalize(raw.trim(), spellingMode)
        text = applyMap(text, corrections)
        text = applyMap(text, emojiMap)
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun applyMap(text: String, map: Map<String, String>): String {
        if (map.isEmpty()) return text
        var output = text
        val ordered = map.keys.sortedByDescending { it.length }
        for (key in ordered) {
            val value = map[key] ?: continue
            output = output.replace(Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE), value)
        }
        return output
    }
}
