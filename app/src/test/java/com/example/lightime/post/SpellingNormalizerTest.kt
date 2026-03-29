package com.example.lightime.post

import org.junit.Assert.assertEquals
import org.junit.Test

class SpellingNormalizerTest {
    private val processor = TextPostProcessor()

    @Test
    fun explicitSpellMode_joinsCommandedLetters() {
        val out = processor.process(
            "please spell c h u t z p a h end spell now",
            emptyMap(),
            emptyMap(),
            SpellingNormalizer.MODE_EXPLICIT
        )
        assertEquals("please chutzpah now", out)
    }

    @Test
    fun autoMode_joinsSpacedLetters() {
        val out = processor.process(
            "c h a t is fun",
            emptyMap(),
            emptyMap(),
            SpellingNormalizer.MODE_AUTO
        )
        assertEquals("chat is fun", out)
    }

    @Test
    fun correctionsThenEmoji_appliesExpectedOrder() {
        val out = processor.process(
            "deep gram emoji thumbs up",
            mapOf("deep gram" to "Deepgram"),
            mapOf("emoji thumbs up" to "👍"),
            SpellingNormalizer.MODE_OFF
        )
        assertEquals("Deepgram 👍", out)
    }
}
