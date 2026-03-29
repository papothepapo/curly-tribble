package com.example.lightime.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.lightime.R
import com.example.lightime.db.DictionaryDbHelper
import com.example.lightime.post.TextPostProcessor
import com.example.lightime.stt.AudioRecorderManager
import com.example.lightime.stt.DeepgramStreamingClient
import com.example.lightime.t9.T9Engine
import com.example.lightime.util.SettingsStore
import java.util.Timer
import java.util.TimerTask

class LightImeService : InputMethodService() {
    private lateinit var settings: SettingsStore
    private lateinit var dbHelper: DictionaryDbHelper
    private lateinit var t9Engine: T9Engine

    private lateinit var statusLine: TextView
    private lateinit var suggestionButtons: List<Button>

    private val typedBuffer = StringBuilder()
    private val digitBuffer = StringBuilder()
    private var upper = false

    // Multi-tap state (800ms timeout)
    private var lastDigit: Char? = null
    private var tapIndex = 0
    private var tapTimer: Timer? = null

    private val deepgram = DeepgramStreamingClient()
    private val audio = AudioRecorderManager()
    private val postProcessor = TextPostProcessor()

    private val dictatedFinal = StringBuilder()
    private var interimSegment = ""

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

    private fun onDigitKey(digit: Char, chars: String) {
        val letter: Char
        if (lastDigit == digit) {
            tapIndex = (tapIndex + 1) % chars.length
            typedBuffer.setLength(typedBuffer.length - 1)
            letter = chars[tapIndex]
        } else {
            tapIndex = 0
            letter = chars[tapIndex]
            digitBuffer.append(digit)
        }
        val out = if (upper) letter.uppercaseChar() else letter
        typedBuffer.append(out)
        currentInputConnection.setComposingText(typedBuffer.toString(), 1)
        lastDigit = digit
        restartTapTimeout()
        updateSuggestions()
    }

    private fun restartTapTimeout() {
        tapTimer?.cancel()
        tapTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    lastDigit = null
                }
            }, 800)
        }
    }

    private fun updateSuggestions() {
        val suggestions = t9Engine.suggestions(digitBuffer.toString(), 3)
        suggestionButtons.forEachIndexed { idx, button -> button.text = suggestions.getOrElse(idx) { "" } }
    }

    private fun currentWord(): String = if (typedBuffer.isNotBlank()) typedBuffer.toString() else suggestionButtons.firstOrNull { it.text.isNotBlank() }?.text?.toString().orEmpty()

    private fun commitWord(word: String) {
        if (word.isBlank()) {
            currentInputConnection.commitText(" ", 1)
            return
        }
        currentInputConnection.finishComposingText()
        currentInputConnection.commitText("$word ", 1)
        dbHelper.upsertUserWord(word.lowercase())
        typedBuffer.setLength(0)
        digitBuffer.setLength(0)
        updateSuggestions()
    }

    private fun backspaceSingle() {
        if (typedBuffer.isNotEmpty()) {
            typedBuffer.setLength(typedBuffer.length - 1)
            if (digitBuffer.isNotEmpty()) digitBuffer.setLength(digitBuffer.length - 1)
            currentInputConnection.setComposingText(typedBuffer.toString(), 1)
            updateSuggestions()
        } else {
            currentInputConnection.deleteSurroundingText(1, 0)
        }
    }

    private fun deleteWord() {
        currentInputConnection.deleteSurroundingText(20, 0)
        typedBuffer.setLength(0)
        digitBuffer.setLength(0)
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
                    if (dictatedFinal.isNotEmpty()) dictatedFinal.append(' ')
                    dictatedFinal.append(text.trim())
                    interimSegment = ""
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
    }

    private fun showStatus(msg: String) {
        statusLine.post { statusLine.text = msg }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val caps = settings.autoCapEnabled() && info?.inputType?.and(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        upper = caps
    }
}
