package com.example.lightime.stt

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DeepgramStreamingClient {
    interface Listener {
        fun onInterim(text: String)
        fun onFinalChunk(text: String, speechFinal: Boolean)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null

    fun connect(apiKey: String, language: String, endpointing: Int, keyterms: List<String>, listener: Listener) {
        val urlBuilder = StringBuilder("wss://api.deepgram.com/v1/listen")
            .append("?model=nova-3")
            .append("&language=").append(language)
            .append("&interim_results=true")
            .append("&smart_format=true")
            .append("&punctuate=true")
            .append("&dictation=true")
            .append("&encoding=linear16")
            .append("&sample_rate=16000")
            .append("&endpointing=").append(endpointing)
        keyterms.forEach { term -> urlBuilder.append("&keyterm=").append(URLEncoder.encode(term, "UTF-8")) }

        val req = Request.Builder()
            .url(urlBuilder.toString())
            .header("Authorization", "Token $apiKey")
            .build()

        socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "Metadata" -> Unit
                    "Results" -> {
                        val alt = json.optJSONObject("channel")
                            ?.optJSONArray("alternatives")
                            ?.optJSONObject(0)
                        val transcript = alt?.optString("transcript").orEmpty()
                        if (transcript.isBlank()) return
                        val isFinal = json.optBoolean("is_final", false)
                        val speechFinal = json.optBoolean("speech_final", false)
                        if (isFinal) listener.onFinalChunk(transcript, speechFinal)
                        else listener.onInterim(transcript)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError("Dictation error: ${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    fun sendAudio(buffer: ByteArray, size: Int) {
        socket?.send(buffer.copyOf(size).toByteString())
    }

    fun finalizeAndClose() {
        socket?.send("{\"type\":\"Finalize\"}")
        socket?.close(1000, "done")
        socket = null
    }
}
