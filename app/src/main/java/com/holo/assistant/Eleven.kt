package com.holo.assistant

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Natural voice from ElevenLabs. Returns an mp3 file to play. */
object Eleven {
    private const val DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"

    fun synth(dir: File, text: String): File {
        val voice = Store.elevenVoice.ifBlank { DEFAULT_VOICE }
        val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_64")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.doOutput = true
        c.setRequestProperty("xi-api-key", Store.elevenKey)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "audio/mpeg")
        val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw RuntimeException("Eleven error ${c.responseCode}")
        val f = File(dir, "voice_${System.nanoTime()}.mp3")
        c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }
}
