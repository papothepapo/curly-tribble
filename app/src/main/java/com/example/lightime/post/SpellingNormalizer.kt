package com.example.lightime.post

class SpellingNormalizer {
    companion object {
        const val MODE_OFF = 0
        const val MODE_AUTO = 1
        const val MODE_EXPLICIT = 2
        const val MODE_BOTH = 3
    }

    fun normalize(input: String, mode: Int): String {
        var text = input
        if (mode == MODE_EXPLICIT || mode == MODE_BOTH) text = applyExplicitSpell(text)
        if (mode == MODE_AUTO || mode == MODE_BOTH) text = applyAutoJoin(text)
        return text
    }

    // "spell c h a t end spell" => "chat"
    private fun applyExplicitSpell(input: String): String {
        val regex = Regex("(?i)spell\\s+((?:[a-z]\\s+)+[a-z])\\s+end\\s+spell")
        return regex.replace(input) { m -> m.groupValues[1].replace(" ", "") }
    }

    // joins isolated letter runs of length>=3 to limit false positives.
    private fun applyAutoJoin(input: String): String {
        val regex = Regex("\\b(?:[a-zA-Z]\\s+){2,}[a-zA-Z]\\b")
        return regex.replace(input) { it.value.replace(" ", "") }
    }
}
