package com.holo.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    // If one model is overloaded, we rotate to the next one.
    private val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash")

    private class Busy(msg: String) : Exception(msg)

    fun part(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun userMsg(text: String): JSONArray = JSONArray().put(part("user", text))

    fun generate(system: String, contents: JSONArray, json: Boolean): String {
        val key = Store.apiKey
        if (key.isBlank()) throw IllegalStateException("Add your Gemini API key first (KEY button).")

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        for (attempt in 0 until 5) {
            val model = MODELS[attempt % MODELS.size]
            try {
                return call(model, key, body)
            } catch (e: Busy) {
                Thread.sleep(1500L * (attempt + 1))
            } catch (e: java.io.IOException) {
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw RuntimeException("Google's AI is busy right now. Wait a minute and try again.")
    }

    private fun call(model: String, key: String, body: JSONObject): String {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 503 || code == 429 || code == 500 || code == 504) throw Busy("busy $code")
        if (code !in 200..299) throw RuntimeException("AI error $code: ${text.take(200)}")

        val cand = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
        return cand.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
