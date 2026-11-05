package com.holo.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    private const val MODEL = "gemini-flash-latest"

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

        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
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
        if (code !in 200..299) throw RuntimeException("AI error $code: ${text.take(200)}")

        val cand = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
        return cand.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }
}
