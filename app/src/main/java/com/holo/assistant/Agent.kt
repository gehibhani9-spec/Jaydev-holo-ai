package com.holo.assistant

import org.json.JSONObject

/** The "brain loop": look at the screen, ask the AI for the next action, do it, repeat. */
object Agent {
    @Volatile var confirm: (String) -> Boolean = { false }
    @Volatile var cancelled = false

    // Anything that looks irreversible needs a yes from the user.
    private val risky = Regex("send|pay|transfer|buy|purchase|order|delete|remove|confirm", RegexOption.IGNORE_CASE)

    private const val SYSTEM = """You control an Android phone for the user by reading the screen and choosing ONE action at a time.
Reply with a single JSON object: {"say":"short status for the user","action":"...", ...}
Actions:
- tap: {"action":"tap","id":N}
- type: {"action":"type","id":N,"text":"..."}  (id -1 means the focused text field)
- scroll: {"action":"scroll","dir":"down"} or "up"
- back, home, wait: {"action":"back"}
- open_app: {"action":"open_app","name":"WhatsApp"}
- ask: {"action":"ask","question":"..."}  (yes/no question to the user)
- done: {"action":"done","message":"short result for the user"}
Rules:
- ids refer only to the numbered SCREEN list given in this step.
- If the needed app is not open, use open_app first.
- Do not repeat a failed action more than twice; try another route.
- Never enter passwords, OTPs or card details. Use done and tell the user to do that part.
- Ask before irreversible things (sending money, deleting, posting publicly).
- Use done as soon as the goal is finished, or if it cannot be done."""

    fun run(goal: String, status: (String) -> Unit): String {
        val svc = AgentService.instance
            ?: return "Turn on Holo in Accessibility settings first (ACCESS button)."
        cancelled = false
        val history = ArrayList<String>()

        for (step in 1..25) {
            if (cancelled) return "Stopped."
            Thread.sleep(900)
            val screen = svc.snapshot()
            val recent = history.takeLast(12).joinToString("\n").ifBlank { "(none)" }
            val prompt = "GOAL: $goal\nACTIONS SO FAR:\n$recent\n\nSCREEN:\n$screen"

            val raw = try {
                Gemini.generate(SYSTEM, Gemini.userMsg(prompt), true)
            } catch (e: Exception) {
                return "Brain error: ${e.message}"
            }
            val j = try {
                JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim())
            } catch (e: Exception) {
                history.add("(unreadable reply)")
                continue
            }

            val say = j.optString("say")
            if (say.isNotBlank()) status(say)

            when (j.optString("action")) {
                "tap" -> {
                    val id = j.optInt("id", -1)
                    val lbl = svc.label(id)
                    if (risky.containsMatchIn(lbl) && !confirm("Tap \"$lbl\"?")) return "Cancelled by you."
                    history.add("tap [$id] \"$lbl\" -> ${svc.tap(id)}")
                }
                "type" -> {
                    val id = j.optInt("id", -1)
                    val text = j.optString("text")
                    history.add("type \"$text\" into [$id] -> ${svc.typeText(id, text)}")
                }
                "scroll" -> {
                    val down = j.optString("dir", "down") != "up"
                    history.add("scroll ${if (down) "down" else "up"} -> ${svc.scroll(down)}")
                }
                "back" -> history.add("back -> ${svc.back()}")
                "home" -> history.add("home -> ${svc.home()}")
                "wait" -> history.add("wait")
                "open_app" -> {
                    val name = j.optString("name")
                    history.add("open_app $name -> ${svc.openApp(name)}")
                }
                "ask" -> {
                    val q = j.optString("question")
                    val ok = confirm(q)
                    if (!ok) return "Cancelled by you."
                    history.add("asked \"$q\" -> user said yes")
                }
                "done" -> return j.optString("message", "Done.")
                else -> history.add("(unknown action)")
            }
        }
        return "I ran out of steps before finishing."
    }
}
