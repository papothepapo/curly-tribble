package com.example.lightime.ime

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.content.pm.PackageManager
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.lightime.R
import com.example.lightime.db.DictionaryDbHelper
import com.example.lightime.post.TextPostProcessor
import com.example.lightime.settings.SettingsActivity
import com.example.lightime.stt.AudioRecorderManager
import com.example.lightime.stt.DeepgramStreamingClient
import com.example.lightime.t9.T9Engine
import com.example.lightime.util.SettingsStore

class LightImeService : InputMethodService() {
    private lateinit var settings: SettingsStore
    private lateinit var dbHelper: DictionaryDbHelper
    private lateinit var t9Engine: T9Engine

    private lateinit var statusLine: TextView
    private lateinit var suggestionButtons: List<Button>
    private var hasTouchscreen: Boolean = true

    private val digitBuffer = StringBuilder()
    private val multiTapBuffer = StringBuilder()
    private var lastTapDigit: Char? = null
    private var lastTapIndex = 0
    private var lastTapAtMs = 0L
    private val multiTapTimeoutMs = 900L
    private var upper = false

    private var dictationActive = false

    private val deepgram = DeepgramStreamingClient()
    private val audio = AudioRecorderManager()
    private val postProcessor = TextPostProcessor()

    private val dictatedFinal = StringBuilder()
    private var interimSegment = ""
    private var lastFinalChunk = ""

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(applicationContext)
        dbHelper = DictionaryDbHelper(applicationContext)
        dbHelper.ensureSeedLoaded()
        t9Engine = T9Engine(dbHelper.readableDatabase)
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.ime_view, null)
        statusLine = root.findViewById(R.id.statusLine)
        hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

        suggestionButtons = listOf(
            root.findViewById(R.id.suggestion1),
            root.findViewById(R.id.suggestion2),
            root.findViewById(R.id.suggestion3)
        )
        suggestionButtons.forEach { btn ->
            btn.setOnClickListener {
                val text = btn.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    commitWord(text)
                }
            }
        }

        bindDigitKey(root, R.id.key2, '2', "abc")
        bindDigitKey(root, R.id.key3, '3', "def")
        bindDigitKey(root, R.id.key4, '4', "ghi")
        bindDigitKey(root, R.id.key5, '5', "jkl")
        bindDigitKey(root, R.id.key6, '6', "mno")
        bindDigitKey(root, R.id.key7, '7', "pqrs")
        bindDigitKey(root, R.id.key8, '8', "tuv")
        bindDigitKey(root, R.id.key9, '9', "wxyz")

        root.findViewById<Button>(R.id.keySettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        if (hasTouchscreen) {
            applyTouchscreenKeyLabels(root)
        } else {
            root.findViewById<View>(R.id.t9Grid).visibility = View.GONE
            root.findViewById<View>(R.id.actionRow).visibility = View.GONE
            showStatus("Use hardware keypad for T9 • predictions above")
        }

        root.findViewById<Button>(R.id.key0).setOnClickListener { commitWord(currentWord()) }
        root.findViewById<Button>(R.id.key1).setOnClickListener { currentInputConnection.commitText(".", 1) }
        root.findViewById<Button>(R.id.keyHash).setOnClickListener { upper = !upper; showStatus(if (upper) "ABC" else "abc") }
        root.findViewById<Button>(R.id.keyStar).setOnClickListener { currentInputConnection.commitText(",", 1) }
        root.findViewById<Button>(R.id.keyEnter).setOnClickListener { currentInputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)) }

        root.findViewById<Button>(R.id.keyBackspace).apply {
            setOnClickListener { backspaceSingle() }
            setOnLongClickListener { deleteWord(); true }
        }

        root.findViewById<Button>(R.id.keyDictation).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startDictation()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDictationAndFinalize()
            }
            true
        }

        return root
    }

    private fun bindDigitKey(root: View, id: Int, digit: Char, chars: String) {
        root.findViewById<Button>(id).setOnClickListener { onDigitKey(digit, chars) }
    }

    private fun applyTouchscreenKeyLabels(root: View) {
        root.findViewById<Button>(R.id.key2).text = "2 abc"
        root.findViewById<Button>(R.id.key3).text = "3 def"
        root.findViewById<Button>(R.id.key4).text = "4 ghi"
        root.findViewById<Button>(R.id.key5).text = "5 jkl"
        root.findViewById<Button>(R.id.key6).text = "6 mno"
        root.findViewById<Button>(R.id.key7).text = "7 pqrs"
        root.findViewById<Button>(R.id.key8).text = "8 tuv"
        root.findViewById<Button>(R.id.key9).text = "9 wxyz"
    }

    private fun onDigitKey(digit: Char, _chars: String) {
        if (!settings.predictiveT9Enabled()) {
            handleMultiTapDigit(digit)
            return
        }
        digitBuffer.append(digit)
        showComposingWord()
        updateSuggestions()
    }

    private fun showComposingWord() {
        if (!settings.predictiveT9Enabled()) {
            val text = displayWithCase(multiTapBuffer.toString())
            currentInputConnection.setComposingText(text, 1)
            return
        }
        val suggestion = topSuggestion()
        if (suggestion.isBlank()) {
            if (hasTouchscreen) {
                currentInputConnection.setComposingText(digitBuffer.toString(), 1)
            } else {
                currentInputConnection.setComposingText("", 1)
            }
            return
        }
        val displayed = if (upper && suggestion.isNotBlank()) {
            suggestion.replaceFirstChar { it.uppercaseChar() }
        } else {
            suggestion
        }
        currentInputConnection.setComposingText(displayed, 1)
    }

    private fun updateSuggestions() {
        if (!settings.predictiveT9Enabled()) {
            suggestionButtons.forEach { it.text = "" }
            return
        }
        val suggestions = t9Engine.suggestions(digitBuffer.toString(), 3)
        suggestionButtons.forEachIndexed { idx, button -> button.text = suggestions.getOrElse(idx) { "" } }
    }

    private fun topSuggestion(): String = suggestionButtons.firstOrNull { it.text.isNotBlank() }?.text?.toString().orEmpty()

    private fun currentWord(): String {
        if (!settings.predictiveT9Enabled()) {
            return displayWithCase(multiTapBuffer.toString())
        }
        val suggestion = topSuggestion()
        if (suggestion.isNotBlank()) {
            return if (upper) suggestion.replaceFirstChar { it.uppercaseChar() } else suggestion
        }
        return digitBuffer.toString()
    }

    private fun commitWord(word: String) {
        if (word.isBlank()) {
            currentInputConnection.commitText(" ", 1)
            return
        }
        currentInputConnection.commitText("$word ", 1)
        dbHelper.upsertUserWord(word.lowercase())
        digitBuffer.setLength(0)
        multiTapBuffer.setLength(0)
        clearMultiTapState()
        updateSuggestions()
        currentInputConnection.finishComposingText()
    }

    private fun backspaceSingle() {
        if (!settings.predictiveT9Enabled() && multiTapBuffer.isNotEmpty()) {
            multiTapBuffer.setLength(multiTapBuffer.length - 1)
            showComposingWord()
            return
        }
        if (digitBuffer.isNotEmpty()) {
            if (digitBuffer.isNotEmpty()) digitBuffer.setLength(digitBuffer.length - 1)
            showComposingWord()
            updateSuggestions()
        } else {
            currentInputConnection.deleteSurroundingText(1, 0)
        }
    }

    private fun deleteWord() {
        currentInputConnection.deleteSurroundingText(20, 0)
        digitBuffer.setLength(0)
        multiTapBuffer.setLength(0)
        clearMultiTapState()
        currentInputConnection.finishComposingText()
        updateSuggestions()
    }

    private fun startDictation() {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) {
            showStatus("Set Deepgram API key in Settings")
            Toast.makeText(this, "Missing Deepgram API key", Toast.LENGTH_SHORT).show()
            return
        }

        dictatedFinal.setLength(0)
        interimSegment = ""
        lastFinalChunk = ""
        showStatus("Listening…")

        deepgram.connect(
            apiKey = apiKey,
            language = settings.language(),
            endpointing = settings.endpointingMs(),
            keyterms = settings.keyterms(),
            listener = object : DeepgramStreamingClient.Listener {
                override fun onInterim(text: String) {
                    interimSegment = text
                    if (settings.interimEnabled()) {
                        currentInputConnection.setComposingText((dictatedFinal.toString() + " " + interimSegment).trim(), 1)
                    }
                }

                override fun onFinalChunk(text: String, speechFinal: Boolean) {
                    val normalized = text.trim()
                    if (normalized.isBlank()) return
                    if (normalized == lastFinalChunk) return

                    val appendSegment = when {
                        lastFinalChunk.isNotBlank() && normalized.startsWith(lastFinalChunk) ->
                            normalized.removePrefix(lastFinalChunk).trim()
                        else -> normalized
                    }
                    if (appendSegment.isBlank()) {
                        lastFinalChunk = normalized
                        return
                    }

                    if (dictatedFinal.isNotEmpty()) dictatedFinal.append(' ')
                    dictatedFinal.append(appendSegment)
                    interimSegment = ""
                    lastFinalChunk = normalized
                    currentInputConnection.setComposingText(dictatedFinal.toString(), 1)
                    if (speechFinal) dictatedFinal.append(' ')
                }

                override fun onError(message: String) {
                    showStatus(message)
                }

                override fun onClosed() {
                    showStatus("Dictation stopped")
                }
            }
        )

        audio.start(object : AudioRecorderManager.Listener {
            override fun onAudioChunk(buffer: ByteArray, size: Int) {
                deepgram.sendAudio(buffer, size)
            }

            override fun onError(message: String) {
                showStatus(message)
            }
        })
        dictationActive = true
    }

    private fun stopDictationAndFinalize() {
        audio.stop()
        deepgram.finalizeAndClose()

        val finalText = buildString {
            append(dictatedFinal.toString().trim())
            if (interimSegment.isNotBlank()) {
                if (isNotBlank()) append(' ')
                append(interimSegment.trim())
            }
        }
        val processed = postProcessor.process(
            finalText,
            corrections = settings.correctionsMap(),
            emojiMap = settings.emojiMap(),
            spellingMode = settings.spellingMode()
        )

        currentInputConnection.finishComposingText()
        if (processed.isNotBlank()) currentInputConnection.commitText("$processed ", 1)
        showStatus("Ready")
        dictationActive = false
    }

    private fun showStatus(msg: String) {
        statusLine.post { statusLine.text = msg }
    }

    private fun handleMultiTapDigit(digit: Char) {
        val now = System.currentTimeMillis()
        val chars = charsForDigit(digit)
        if (chars.isEmpty()) return

        val sameKey = lastTapDigit == digit && now - lastTapAtMs <= multiTapTimeoutMs && multiTapBuffer.isNotEmpty()
        if (sameKey) {
            lastTapIndex = (lastTapIndex + 1) % chars.length
            multiTapBuffer.setCharAt(multiTapBuffer.length - 1, chars[lastTapIndex])
        } else {
            lastTapDigit = digit
            lastTapIndex = 0
            multiTapBuffer.append(chars[0])
        }
        lastTapAtMs = now
        showComposingWord()
        updateSuggestions()
    }

    private fun charsForDigit(digit: Char): String = when (digit) {
        '2' -> "abc"
        '3' -> "def"
        '4' -> "ghi"
        '5' -> "jkl"
        '6' -> "mno"
        '7' -> "pqrs"
        '8' -> "tuv"
        '9' -> "wxyz"
        else -> ""
    }

    private fun displayWithCase(text: String): String {
        if (text.isBlank() || !upper) return text
        val first = text.first().uppercaseChar()
        return first + text.substring(1)
    }

    private fun clearMultiTapState() {
        lastTapDigit = null
        lastTapIndex = 0
        lastTapAtMs = 0L
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val caps = settings.autoCapEnabled() && info?.inputType?.and(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        upper = caps
        digitBuffer.setLength(0)
        multiTapBuffer.setLength(0)
        clearMultiTapState()
        updateSuggestions()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            micHardwareKeyCode() -> {
                if (!dictationActive) startDictation() else stopDictationAndFinalize()
                return true
            }
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0 -> {
                commitWord(currentWord())
                return true
            }
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_NUMPAD_2 -> {
                onDigitKey('2', "abc")
                return true
            }
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_NUMPAD_3 -> {
                onDigitKey('3', "def")
                return true
            }
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_NUMPAD_4 -> {
                onDigitKey('4', "ghi")
                return true
            }
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_NUMPAD_5 -> {
                onDigitKey('5', "jkl")
                return true
            }
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_NUMPAD_6 -> {
                onDigitKey('6', "mno")
                return true
            }
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_NUMPAD_7 -> {
                onDigitKey('7', "pqrs")
                return true
            }
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_NUMPAD_8 -> {
                onDigitKey('8', "tuv")
                return true
            }
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_9 -> {
                onDigitKey('9', "wxyz")
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                backspaceSingle()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun micHardwareKeyCode(): Int = when (settings.micHardwareKey()) {
        "SEARCH" -> KeyEvent.KEYCODE_SEARCH
        "CAMERA" -> KeyEvent.KEYCODE_CAMERA
        "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP
        "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
        else -> KeyEvent.KEYCODE_CALL
    }

}
