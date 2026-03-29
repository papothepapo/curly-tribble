package com.example.lightime.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorderManager {
    interface Listener {
        fun onAudioChunk(buffer: ByteArray, size: Int)
        fun onError(message: String)
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var reusableBuffer: ByteArray? = null

    fun start(listener: Listener) {
        if (running.get()) return
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            listener.onError("Mic init failed")
            return
        }
        val actualBuffer = minBuffer * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            actualBuffer
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("Microphone unavailable")
            return
        }
        reusableBuffer = ByteArray(actualBuffer)
        audioRecord = record
        running.set(true)
        record.startRecording()
        thread = Thread {
            val buf = reusableBuffer ?: return@Thread
            while (running.get()) {
                val size = record.read(buf, 0, buf.size)
                if (size > 0) listener.onAudioChunk(buf, size)
            }
        }.apply { start() }
    }

    fun stop() {
        running.set(false)
        thread?.join(400)
        thread = null
        audioRecord?.runCatching {
            stop()
            release()
        }
        audioRecord = null
    }
}
