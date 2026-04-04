package com.example.lightime.ime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class LightImeService : InputMethodService() {
    private lateinit var settings: SettingsStore
    private lateinit var dbHelper: DictionaryDbHelper
    private var t9Engine: T9Engine? = null

    private var statusLine: TextView? = null
    private var streamingIndicator: View? = null
    private var streamingLabel: TextView? = null
    private var dictationButton: Button? = null
    private var suggestionButtons: List<Button> = emptyList()
    private var currentSuggestions: List<String> = emptyList()
    private var hasTouchscreen: Boolean = true
    private var showOnscreenT9Keypad: Boolean = true
    private var lastStatusMessage = "Ready"
    private var dictationUiState = DictationUiState.IDLE

    private data class KeyBinding(val keyCode: Int, val longPress: Boolean)
    private val pendingLongPressKeys = mutableSetOf<Int>()
    private val consumedLongPressKeys = mutableSetOf<Int>()

    private val digitBuffer = StringBuilder()
    private val multiTapBuffer = StringBuilder()
    private var lastTapDigit: Char? = null
    private var lastTapIndex = 0
    private var lastTapAtMs = 0L
    private val multiTapTimeoutMs = 900L
    private var inputMode = InputMode.T9

    private var dictationActive = false
    private var dictationSessionId = 0L

    private val deepgram = DeepgramStreamingClient()
    private val audio = AudioRecorderManager()
    private val postProcessor = TextPostProcessor()

    private val dictatedFinal = StringBuilder()
    private var interimSegment = ""
    private var lastFinalChunk = ""
    private var pendingFinalizeSessionId: Long? = null
    private var committedDictationSessionId: Long? = null
    private var lastCustomWordsSignature = Int.MIN_VALUE
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeating = false
    private val repeatingDeleteRunnable = object : Runnable {
        override fun run() {
            if (!backspaceRepeating) return
            backspaceSingle()
            mainHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        settings = SettingsStore(applicationContext)
        dbHelper = DictionaryDbHelper(applicationContext)
        createNotificationChannel()
        backgroundExecutor.execute {
            dbHelper.ensureSeedLoaded()
            dbHelper.replaceCustomWords(settings.customDictionaryWords())
            lastCustomWordsSignature = settings.customDictionaryWords().joinToString("\n").hashCode()
            t9Engine = T9Engine(dbHelper.readableDatabase)
            mainHandler.post {
                updateSuggestions()
                showComposingWord()
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.ime_view, null)
        statusLine = root.findViewById(R.id.statusLine)
        streamingIndicator = root.findViewById(R.id.streamingIndicator)
        streamingLabel = root.findViewById(R.id.streamingLabel)

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

        showOnscreenT9Keypad = hasTouchscreen && !settings.forceHideOnscreenT9Keypad()
        if (showOnscreenT9Keypad) {
            applyTouchscreenKeyLabels(root)
        } else {
            root.findViewById<View>(R.id.t9Grid).visibility = View.GONE
            root.findViewById<View>(R.id.actionRow).visibility = View.GONE
            showStatus("Use hardware keypad for T9 • predictions above")
        }

        root.findViewById<Button>(R.id.key0).setOnClickListener {
            if (inputMode == InputMode.NUMERIC) {
                currentInputConnection?.commitText("0", 1)
            } else {
                commitWord(currentWord())
            }
        }
        root.findViewById<Button>(R.id.key1).setOnClickListener {
            if (inputMode == InputMode.NUMERIC) {
                currentInputConnection?.commitText("1", 1)
            } else {
                commitPunctuation(".")
            }
        }
        root.findViewById<Button>(R.id.keyHash).apply {
            setOnClickListener { onHashShortPress() }
            setOnLongClickListener {
                onHashLongPress()
                true
            }
        }
        root.findViewById<Button>(R.id.keyStar).setOnClickListener { commitPunctuation(",") }
        root.findViewById<Button>(R.id.keyEnter).setOnClickListener {
            commitComposingWordIfAny()
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }

        root.findViewById<Button>(R.id.keyBackspace).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    backspaceSingle()
                    backspaceRepeating = true
                    mainHandler.postDelayed(repeatingDeleteRunnable, BACKSPACE_INITIAL_REPEAT_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopBackspaceRepeat()
                    true
                }
                else -> false
            }
        }

        val keyDictation = root.findViewById<Button>(R.id.keyDictation)
        dictationButton = keyDictation
        keyDictation.visibility = if (settings.sttEnabled()) View.VISIBLE else View.GONE
        keyDictation.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startDictation()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDictationAndFinalize()
            }
            true
        }
        renderDictationUi()
        showStatus(lastStatusMessage)

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
        if (inputMode == InputMode.NUMERIC) {
            clearCompositionBuffers()
            currentInputConnection?.commitText(digit.toString(), 1)
            return
        }
        if (inputMode != InputMode.T9) {
            handleMultiTapDigit(digit)
            return
        }
        digitBuffer.append(digit)
        updateSuggestions()
        showComposingWord()
    }

    private fun showComposingWord() {
        if (inputMode == InputMode.NUMERIC) {
            currentInputConnection?.finishComposingText()
            return
        }
        if (inputMode != InputMode.T9) {
            currentInputConnection?.setComposingText(displayWithCase(multiTapBuffer.toString()), 1)
            return
        }
        val suggestion = topSuggestion()
        val displayText = when {
            suggestion.isNotBlank() -> displayWithCase(suggestion)
            digitBuffer.isNotEmpty() -> digitBuffer.toString()
            else -> ""
        }
        currentInputConnection?.setComposingText(displayText, 1)
    }

    private fun updateSuggestions() {
        if (inputMode == InputMode.NUMERIC) {
            currentSuggestions = emptyList()
            suggestionButtons.forEach { it.text = "" }
            return
        }
        if (inputMode != InputMode.T9) {
            currentSuggestions = emptyList()
            suggestionButtons.forEach { it.text = "" }
            return
        }
        val engine = t9Engine
        if (engine == null) {
            currentSuggestions = emptyList()
            suggestionButtons.forEach { it.text = "" }
            return
        }
        currentSuggestions = engine.suggestions(digitBuffer.toString(), 3)
        suggestionButtons.forEachIndexed { idx, button -> button.text = currentSuggestions.getOrElse(idx) { "" } }
    }

    private fun topSuggestion(): String = currentSuggestions.firstOrNull().orEmpty()

    private fun currentWord(): String {
        if (inputMode == InputMode.NUMERIC) return ""
        if (inputMode != InputMode.T9) {
            return displayWithCase(multiTapBuffer.toString())
        }
        val suggestion = topSuggestion()
        if (suggestion.isNotBlank()) return displayWithCase(suggestion)
        return digitBuffer.toString()
    }

    private fun commitWord(word: String) {
        val ic = currentInputConnection ?: return
        if (word.isBlank()) {
            ic.commitText(" ", 1)
            clearCompositionBuffers()
            return
        }
        ic.commitText("$word ", 1)
        if (shouldLearnWord(word)) learnWord(word)
        clearCompositionBuffers()
    }

    private fun commitPunctuation(mark: String) {
        val ic = currentInputConnection ?: return
        val word = currentWord()
        if (word.isNotBlank()) {
            ic.commitText(word, 1)
            if (shouldLearnWord(word)) learnWord(word)
        }
        ic.commitText(mark, 1)
        clearCompositionBuffers()
    }

    private fun clearCompositionBuffers() {
        digitBuffer.setLength(0)
        multiTapBuffer.setLength(0)
        clearMultiTapState()
        currentInputConnection?.finishComposingText()
        updateSuggestions()
    }

    private fun commitComposingWordIfAny() {
        val word = currentWord()
        if (word.isBlank()) return
        val ic = currentInputConnection ?: return
        ic.commitText(word, 1)
        if (shouldLearnWord(word)) learnWord(word)
        clearCompositionBuffers()
    }

    private fun learnWord(word: String) {
        val normalized = DictionaryDbHelper.normalizeWord(word)
        if (normalized.isBlank()) return
        dbHelper.upsertUserWord(normalized)
        t9Engine?.invalidateCache()
    }

    private fun shouldLearnWord(word: String): Boolean {
        if (word.isBlank()) return false
        val normalized = DictionaryDbHelper.normalizeWord(word)
        if (normalized.isBlank()) return false
        if (inputMode != InputMode.T9) return true
        return topSuggestion().isNotBlank()
    }

    private fun backspaceSingle() {
        if (inputMode != InputMode.T9 && multiTapBuffer.isNotEmpty()) {
            multiTapBuffer.setLength(multiTapBuffer.length - 1)
            showComposingWord()
            return
        }
        if (digitBuffer.isNotEmpty()) {
            digitBuffer.setLength(digitBuffer.length - 1)
            updateSuggestions()
            showComposingWord()
            return
        }
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeating = false
        mainHandler.removeCallbacks(repeatingDeleteRunnable)
    }

    private fun startDictation() {
        if (dictationActive) return
        if (!settings.sttEnabled()) {
            showStatus("Speech-to-text disabled")
            return
        }

        if (!hasRecordAudioPermission()) {
            showStatus("Microphone permission required")
            Toast.makeText(this, "Grant microphone permission in Settings", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) {
            showStatus("Set Deepgram API key in Settings")
            Toast.makeText(this, "Missing Deepgram API key", Toast.LENGTH_SHORT).show()
            return
        }

        commitComposingWordIfAny()

        dictatedFinal.setLength(0)
        interimSegment = ""
        lastFinalChunk = ""
        pendingFinalizeSessionId = null
        committedDictationSessionId = null
        val sessionId = ++dictationSessionId
        dictationActive = true
        deepgram.close()
        setDictationUiState(DictationUiState.LISTENING)
        showStatus("Streaming audio…")

        deepgram.connect(
            apiKey = apiKey,
            language = settings.language(),
            endpointing = settings.endpointingMs(),
            keyterms = settings.keyterms(),
            listener = object : DeepgramStreamingClient.Listener {
                override fun onInterim(text: String) {
                    mainHandler.post {
                        if (!canAcceptDictationCallbacks(sessionId)) return@post
                        interimSegment = text
                        if (settings.interimEnabled()) {
                            currentInputConnection?.setComposingText((dictatedFinal.toString() + " " + interimSegment).trim(), 1)
                        }
                    }
                }

                override fun onFinalChunk(text: String, speechFinal: Boolean) {
                    mainHandler.post {
                        if (!canAcceptDictationCallbacks(sessionId)) return@post
                        val normalized = text.trim()
                        if (normalized.isBlank()) return@post
                        if (normalized == lastFinalChunk) return@post

                        val appendSegment = suffixDelta(lastFinalChunk, normalized)
                        if (appendSegment.isBlank()) {
                            lastFinalChunk = normalized
                            return@post
                        }

                        if (dictatedFinal.isNotEmpty()) dictatedFinal.append(' ')
                        dictatedFinal.append(appendSegment)
                        interimSegment = ""
                        lastFinalChunk = normalized
                        currentInputConnection?.setComposingText(dictatedFinal.toString(), 1)
                        if (speechFinal) dictatedFinal.append(' ')
                    }
                }

                override fun onError(message: String) {
                    abortDictation(sessionId, message)
                }

                override fun onClosed() {
                    mainHandler.post {
                        if (!canAcceptDictationCallbacks(sessionId)) return@post
                        if (pendingFinalizeSessionId == sessionId) return@post
                        finishDictationUi("Dictation stopped")
                    }
                }
            }
        )

        audio.start(object : AudioRecorderManager.Listener {
            override fun onAudioChunk(buffer: ByteArray, size: Int) {
                if (!canAcceptDictationCallbacks(sessionId)) return
                deepgram.sendAudio(buffer, size)
            }

            override fun onError(message: String) {
                abortDictation(sessionId, message)
            }
        })
    }

    private fun isCurrentDictationSession(sessionId: Long): Boolean {
        return sessionId == dictationSessionId
    }

    private fun canAcceptDictationCallbacks(sessionId: Long): Boolean {
        if (!isCurrentDictationSession(sessionId)) return false
        return dictationActive || pendingFinalizeSessionId == sessionId
    }

    private fun stopDictationAndFinalize() {
        if (!dictationActive) return
        val finishingSessionId = dictationSessionId
        dictationActive = false
        pendingFinalizeSessionId = finishingSessionId

        audio.stop()
        setDictationUiState(DictationUiState.FINALIZING)
        showStatus("Finishing dictation…")
        deepgram.finalize()

        mainHandler.postDelayed({
            if (pendingFinalizeSessionId != finishingSessionId) return@postDelayed
            pendingFinalizeSessionId = null
            deepgram.close()
            commitDictationResult(sessionId = finishingSessionId)
        }, DICTATION_FINALIZE_GRACE_MS)
    }

    private fun abortDictation(sessionId: Long, message: String) {
        mainHandler.post {
            if (!canAcceptDictationCallbacks(sessionId)) return@post
            dictationActive = false
            pendingFinalizeSessionId = null
            audio.stop()
            deepgram.close()
            if (dictatedFinal.isNotEmpty() || interimSegment.isNotBlank()) {
                commitDictationResult(statusMessage = message, sessionId = sessionId)
            } else {
                resetDictationBuffers()
                finishDictationUi(message)
                currentInputConnection?.finishComposingText()
            }
        }
    }

    private fun commitDictationResult(statusMessage: String = "Ready", sessionId: Long = dictationSessionId) {
        if (committedDictationSessionId == sessionId) {
            finishDictationUi(statusMessage)
            return
        }
        committedDictationSessionId = sessionId

        val interimTail = suffixDelta(dictatedFinal.toString().trim(), interimSegment.trim())
        val finalText = buildString {
            append(dictatedFinal.toString().trim())
            if (interimTail.isNotBlank()) {
                if (isNotBlank()) append(' ')
                append(interimTail)
            }
        }
        val processed = postProcessor.process(
            finalText,
            corrections = settings.correctionsMap(),
            emojiMap = settings.emojiMap(),
            spellingMode = settings.spellingMode()
        )

        if (processed.isNotBlank()) currentInputConnection?.commitText("$processed ", 1)
        resetDictationBuffers()
        finishDictationUi(statusMessage)
    }

    private fun showStatus(msg: String) {
        lastStatusMessage = msg
        statusLine?.post { statusLine?.text = msg }
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
        if (text.isBlank()) return text
        return when (inputMode) {
            InputMode.UPPER -> text.uppercase()
            InputMode.LOWER -> text.lowercase()
            else -> text
        }
    }

    private fun clearMultiTapState() {
        lastTapDigit = null
        lastTapIndex = 0
        lastTapAtMs = 0L
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val caps = settings.autoCapEnabled() && info?.inputType?.and(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        inputMode = when {
            settings.predictiveT9Enabled() -> InputMode.T9
            caps -> InputMode.UPPER
            else -> InputMode.LOWER
        }
        clearCompositionBuffers()
        currentInputConnection?.finishComposingText()
        stopBackspaceRepeat()
        syncCustomWordsIfNeeded()
        renderDictationUi()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopBackspaceRepeat()
        stopDictationAndFinalize()
        pendingLongPressKeys.clear()
        consumedLongPressKeys.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        audio.stop()
        deepgram.close()
        cancelStreamingNotification()
        dbHelper.close()
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0 && hasLongPressBindingForKey(keyCode)) {
            pendingLongPressKeys.add(keyCode)
            consumedLongPressKeys.remove(keyCode)
            return true
        }
        if (pendingLongPressKeys.contains(keyCode)) return true
        return if (handleShortPressKey(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        val consumed = handleLongPressKey(keyCode)
        if (consumed) consumedLongPressKeys.add(keyCode)
        return if (consumed) true else super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (pendingLongPressKeys.remove(keyCode)) {
            val wasLongPress = consumedLongPressKeys.remove(keyCode)
            if (wasLongPress) return true
            return if (handleShortPressKey(keyCode)) true else super.onKeyUp(keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleShortPressKey(keyCode: Int): Boolean {
        when (keyCode) {
            bindingKeyCode(settings.micHardwareKey()) -> {
                if (!settings.sttEnabled()) return true
                if (!dictationActive) startDictation() else stopDictationAndFinalize()
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_0 -> {
                if (inputMode == InputMode.NUMERIC) {
                    currentInputConnection?.commitText("0", 1)
                } else {
                    commitWord(currentWord())
                }
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_1 -> {
                if (inputMode == InputMode.NUMERIC) {
                    currentInputConnection?.commitText("1", 1)
                } else {
                    commitPunctuation(".")
                }
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
        }

        if (matchesBinding(settings.spaceHardwareKey(), keyCode, longPress = false)) {
            if (inputMode == InputMode.NUMERIC) {
                currentInputConnection?.commitText("0", 1)
            } else {
                commitWord(currentWord())
            }
            return true
        }
        if (matchesBinding(settings.punctuationHardwareKey(), keyCode, longPress = false)) {
            if (inputMode == InputMode.NUMERIC) {
                currentInputConnection?.commitText("1", 1)
            } else {
                commitPunctuation(".")
            }
            return true
        }
        if (matchesBinding(settings.backspaceHardwareKey(), keyCode, longPress = false)) {
            backspaceSingle()
            return true
        }
        if (matchesBinding(settings.enterHardwareKey(), keyCode, longPress = false)) {
            commitComposingWordIfAny()
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            return true
        }
        if (matchesBinding(settings.symbolHardwareKey(), keyCode, longPress = false)) {
            commitPunctuation(",")
            return true
        }
        if (matchesBinding(settings.modeCycleHardwareKey(), keyCode, longPress = false)) {
            cycleInputMode()
            return true
        }

        return false
    }

    private fun handleLongPressKey(keyCode: Int): Boolean {
        if (matchesBinding(settings.micHardwareKey(), keyCode, longPress = true)) {
            if (!settings.sttEnabled()) return true
            if (!dictationActive) startDictation() else stopDictationAndFinalize()
            return true
        }
        if (matchesBinding(settings.backspaceHardwareKey(), keyCode, longPress = true)) {
            backspaceSingle()
            return true
        }
        if (matchesBinding(settings.enterHardwareKey(), keyCode, longPress = true)) {
            commitComposingWordIfAny()
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            return true
        }
        if (matchesBinding(settings.spaceHardwareKey(), keyCode, longPress = true)) {
            if (inputMode == InputMode.NUMERIC) currentInputConnection?.commitText("0", 1) else commitWord(currentWord())
            return true
        }
        if (matchesBinding(settings.punctuationHardwareKey(), keyCode, longPress = true)) {
            if (inputMode == InputMode.NUMERIC) currentInputConnection?.commitText("1", 1) else commitPunctuation(".")
            return true
        }
        if (matchesBinding(settings.symbolHardwareKey(), keyCode, longPress = true)) {
            commitPunctuation(",")
            return true
        }
        if (matchesBinding(settings.modeCycleHardwareKey(), keyCode, longPress = true)) {
            cycleInputMode()
            return true
        }
        return false
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return if (hasTouchscreen) super.onEvaluateInputViewShown() else true
    }

    private fun hasLongPressBindingForKey(keyCode: Int): Boolean {
        val bindings = listOf(
            parseBinding(settings.micHardwareKey(), KeyEvent.KEYCODE_CALL),
            parseBinding(settings.backspaceHardwareKey(), KeyEvent.KEYCODE_DEL),
            parseBinding(settings.enterHardwareKey(), KeyEvent.KEYCODE_ENTER),
            parseBinding(settings.spaceHardwareKey(), KeyEvent.KEYCODE_0),
            parseBinding(settings.punctuationHardwareKey(), KeyEvent.KEYCODE_1),
            parseBinding(settings.symbolHardwareKey(), KeyEvent.KEYCODE_STAR),
            parseBinding(settings.modeCycleHardwareKey(), KeyEvent.KEYCODE_STAR)
        )
        return bindings.any { it.longPress && it.keyCode == keyCode }
    }

    private fun matchesBinding(rawValue: String, keyCode: Int, longPress: Boolean): Boolean {
        val binding = parseBinding(rawValue, KeyEvent.KEYCODE_UNKNOWN)
        return binding.longPress == longPress && binding.keyCode == keyCode
    }

    private fun parseBinding(value: String, fallback: Int): KeyBinding {
        val isLongPress = value.startsWith("LP_")
        val raw = if (isLongPress) value.removePrefix("LP_") else value
        return KeyBinding(bindingKeyCode(raw, fallback), isLongPress)
    }

    private fun bindingKeyCode(value: String, fallback: Int = KeyEvent.KEYCODE_UNKNOWN): Int = when (value) {
        "NONE" -> KeyEvent.KEYCODE_UNKNOWN
        "KEY_0" -> KeyEvent.KEYCODE_0
        "KEY_1" -> KeyEvent.KEYCODE_1
        "KEY_2" -> KeyEvent.KEYCODE_2
        "KEY_3" -> KeyEvent.KEYCODE_3
        "KEY_4" -> KeyEvent.KEYCODE_4
        "KEY_5" -> KeyEvent.KEYCODE_5
        "KEY_6" -> KeyEvent.KEYCODE_6
        "KEY_7" -> KeyEvent.KEYCODE_7
        "KEY_8" -> KeyEvent.KEYCODE_8
        "KEY_9" -> KeyEvent.KEYCODE_9
        "STAR" -> KeyEvent.KEYCODE_STAR
        "POUND" -> KeyEvent.KEYCODE_POUND
        "DPAD_CENTER" -> KeyEvent.KEYCODE_DPAD_CENTER
        "CALL" -> KeyEvent.KEYCODE_CALL
        "ENDCALL" -> KeyEvent.KEYCODE_ENDCALL
        "DEL" -> KeyEvent.KEYCODE_DEL
        "CLEAR" -> KeyEvent.KEYCODE_CLEAR
        "ENTER" -> KeyEvent.KEYCODE_ENTER
        "SPACE" -> KeyEvent.KEYCODE_SPACE
        "SEARCH" -> KeyEvent.KEYCODE_SEARCH
        "CAMERA" -> KeyEvent.KEYCODE_CAMERA
        "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP
        "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
        else -> fallback
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun onHashShortPress() {
        inputMode = when (inputMode) {
            InputMode.LOWER -> InputMode.UPPER
            InputMode.UPPER -> InputMode.NUMERIC
            InputMode.NUMERIC -> InputMode.LOWER
            InputMode.T9 -> InputMode.UPPER
        }
        clearCompositionBuffers()
        showInputModeStatus()
        showComposingWord()
    }

    private fun onHashLongPress() {
        cycleInputMode()
    }

    private fun cycleInputMode() {
        inputMode = when (inputMode) {
            InputMode.T9 -> InputMode.UPPER
            InputMode.UPPER -> InputMode.LOWER
            InputMode.LOWER -> InputMode.NUMERIC
            InputMode.NUMERIC -> InputMode.T9
        }
        clearCompositionBuffers()
        showInputModeStatus()
        updateSuggestions()
        showComposingWord()
    }

    private fun showInputModeStatus() {
        val modeLabel = when (inputMode) {
            InputMode.T9 -> "T9"
            InputMode.LOWER -> "abc"
            InputMode.UPPER -> "ABC"
            InputMode.NUMERIC -> "123"
        }
        showStatus(modeLabel)
    }

    private fun suffixDelta(previous: String, current: String): String {
        if (current.isBlank()) return ""
        if (previous.isBlank()) return current
        if (current.startsWith(previous)) return current.removePrefix(previous).trim()

        val prevWords = previous.split(Regex("\\s+")).filter { it.isNotBlank() }
        val currWords = current.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (currWords.isEmpty()) return ""

        val maxOverlap = minOf(prevWords.size, currWords.size)
        for (overlap in maxOverlap downTo 1) {
            val prevSuffix = prevWords.takeLast(overlap)
            val currPrefix = currWords.take(overlap)
            if (prevSuffix == currPrefix) {
                return currWords.drop(overlap).joinToString(" ")
            }
        }
        return current
    }

    private fun resetDictationBuffers() {
        dictatedFinal.setLength(0)
        interimSegment = ""
        lastFinalChunk = ""
    }

    private fun finishDictationUi(statusMessage: String) {
        setDictationUiState(DictationUiState.IDLE)
        showStatus(statusMessage)
    }

    private fun syncCustomWordsIfNeeded() {
        val customWords = settings.customDictionaryWords()
        val signature = customWords.joinToString("\n").hashCode()
        if (signature == lastCustomWordsSignature) return
        backgroundExecutor.execute {
            dbHelper.replaceCustomWords(customWords)
            t9Engine?.invalidateCache()
            lastCustomWordsSignature = signature
            mainHandler.post {
                updateSuggestions()
                showComposingWord()
            }
        }
    }

    private fun setDictationUiState(state: DictationUiState) {
        dictationUiState = state
        renderDictationUi()
    }

    private fun renderDictationUi() {
        val state = dictationUiState
        val indicatorVisible = state != DictationUiState.IDLE
        streamingIndicator?.visibility = if (indicatorVisible) View.VISIBLE else View.GONE
        streamingLabel?.text = when (state) {
            DictationUiState.IDLE -> ""
            DictationUiState.LISTENING -> "Streaming audio to Deepgram"
            DictationUiState.FINALIZING -> "Finishing speech capture"
        }
        dictationButton?.text = when (state) {
            DictationUiState.IDLE -> "🎤 Hold"
            DictationUiState.LISTENING -> "● Live"
            DictationUiState.FINALIZING -> "⏳ Wait"
        }
        updateStreamingNotification(state)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            STREAMING_CHANNEL_ID,
            "LightIME dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when LightIME is actively streaming microphone audio."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateStreamingNotification(state: DictationUiState) {
        if (state == DictationUiState.IDLE) {
            cancelStreamingNotification()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val text = when (state) {
            DictationUiState.LISTENING -> "Streaming audio to Deepgram"
            DictationUiState.FINALIZING -> "Finishing speech capture"
            DictationUiState.IDLE -> return
        }

        val notification = NotificationCompat.Builder(this, STREAMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("LightIME dictation active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        NotificationManagerCompat.from(this).notify(STREAMING_NOTIFICATION_ID, notification)
    }

    private fun cancelStreamingNotification() {
        NotificationManagerCompat.from(this).cancel(STREAMING_NOTIFICATION_ID)
    }

    companion object {
        private const val BACKSPACE_INITIAL_REPEAT_DELAY_MS = 350L
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 60L
        private const val DICTATION_FINALIZE_GRACE_MS = 1200L
        private const val STREAMING_CHANNEL_ID = "lightime_dictation"
        private const val STREAMING_NOTIFICATION_ID = 1001
    }

    private enum class InputMode {
        T9,
        LOWER,
        UPPER,
        NUMERIC
    }

    private enum class DictationUiState {
        IDLE,
        LISTENING,
        FINALIZING
    }
}
