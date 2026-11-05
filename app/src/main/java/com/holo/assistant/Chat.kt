package com.holo.assistant

import org.json.JSONArray

/** Free talk mode: casual back-and-forth, saved on the phone. */
object Chat {
    private const val PERSONA = """You are HOLO, a sharp-tongued AI living inside the user's phone.
In free talk mode you talk like a close friend with a savage sense of humor: sarcastic, quick, roast-style comebacks, banter, dark humor.
Your replies are read aloud, so keep them short (1 to 3 sentences), spoken style, no emojis, no markdown.
Roast the user's choices and habits, never their race, religion, gender, disability or other identity, and no slurs.
When they ask a real question, answer it correctly first, then add the attitude."""

    fun reply(user: String): String {
        Store.addChat("user", user)
        val h = Store.history()
        val contents = JSONArray()
        val start = maxOf(0, h.length() - 40)
        for (i in start until h.length()) {
            val m = h.getJSONObject(i)
            val role = if (m.getString("role") == "user") "user" else "model"
            if (contents.length() == 0 && role != "user") continue
            contents.put(Gemini.part(role, m.getString("text")))
        }
        val out = Gemini.generate(PERSONA, contents, false).trim()
        Store.addChat("assistant", out)
        return out
    }
}
