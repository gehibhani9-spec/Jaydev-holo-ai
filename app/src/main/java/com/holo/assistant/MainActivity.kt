package com.holo.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val ACCENT = 0xFFFF4A3D.toInt()

class MainActivity : Activity() {

    private lateinit var holo: HoloView
    private lateinit var status: TextView
    private lateinit var modeLabel: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var tasksPanel: LinearLayout
    private lateinit var micBtn: Button
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var speaking = false
    private var resumed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale("en", "IN")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { runOnUiThread { holo.state = HoloView.State.SPEAK } }
            override fun onDone(id: String?) { runOnUiThread { finishedSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { runOnUiThread { finishedSpeaking() } }
        })

        Agent.confirm = { msg ->
            val latch = CountDownLatch(1)
            val answer = AtomicBoolean(false)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Confirm")
                    .setMessage(msg)
                    .setPositiveButton("Yes") { _, _ -> answer.set(true); latch.countDown() }
                    .setNegativeButton("No") { _, _ -> latch.countDown() }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            }
            latch.await()
            answer.get()
        }

        buildUi()
        refreshMode()
        refreshMic()
        refreshTasks()

        if (Store.apiKey.isBlank()) {
            say("Welcome. Tap KEYS to add your Gemini key, then ACCESS to switch on phone control.", false)
        } else {
            say("Online. Just talk, I'm listening.", false)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        holo = HoloView(this)
        root.addView(holo, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        modeLabel = TextView(this).apply { setTextColor(ACCENT); textSize = 13f; letterSpacing = 0.15f }
        top.addView(modeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("KEYS") { askKeys() })
        top.addView(btn("ACCESS") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        top.addView(btn("TASKS") {
            tasksPanel.visibility = if (tasksPanel.visibility == android.view.View.VISIBLE)
                android.view.View.GONE else android.view.View.VISIBLE
        })
        top.addView(btn("STOP") { Agent.cancelled = true; say("Stopped.", false) })
        col.addView(top)

        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, dp(8), 0, 0) }
        col.addView(status)

        tasksPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt()); setStroke(dp(1), ACCENT); cornerRadius = dp(8).toFloat()
            }
        }
        col.addView(tasksPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        col.addView(android.view.View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        log = TextView(this).apply { setTextColor(0xFFFFB3AA.toInt()); textSize = 13f }
        logScroll = ScrollView(this).apply {
            addView(log)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        col.addView(logScroll, LinearLayout.LayoutParams(-1, dp(140)))

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Type here, or just talk"
            setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 14f; maxLines = 2
        }
        // If an API key is pasted here, save it silently and clear the box so it is never shown.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (captureKey(s?.toString() ?: "")) input.setText("")
            }
        })
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("SEND") { val t = input.text.toString(); input.setText(""); handle(t) })
        micBtn = btn("MIC ON") {
            Store.handsFree = !Store.handsFree
            refreshMic()
            if (Store.handsFree) startListening() else stopListening()
        }
        row.addView(micBtn)
        col.addView(row)

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(ACCENT)
        textSize = 11f
        isAllCaps = true
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setStroke(dp(1), ACCENT); cornerRadius = dp(6).toFloat(); setColor(0x22FF3B30)
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun refreshMode() {
        modeLabel.text = if (Store.freeMode) "MODE: FREE TALK" else "MODE: NORMAL"
        holo.modeText = if (Store.freeMode) "FREE TALK" else "NORMAL"
    }

    private fun refreshMic() {
        micBtn.text = if (Store.handsFree) "MIC ON" else "MIC OFF"
    }

    private fun refreshTasks() {
        tasksPanel.removeAllViews()
        tasksPanel.addView(TextView(this).apply {
            text = "TASKS  (long-press one to delete)"; setTextColor(ACCENT); textSize = 12f
        })
        val t = Store.tasks()
        if (t.isEmpty()) {
            tasksPanel.addView(TextView(this).apply {
                text = "Nothing yet. Say: add task buy milk"; setTextColor(0x88FFFFFF.toInt()); textSize = 14f
                setPadding(0, dp(8), 0, 0)
            })
        }
        t.forEachIndexed { i, s ->
            tasksPanel.addView(TextView(this).apply {
                text = "▸ $s"; setTextColor(Color.WHITE); textSize = 16f
                setPadding(0, dp(8), 0, dp(8))
                setOnLongClickListener { Store.removeTask(i); refreshTasks(); true }
            })
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            log.append(line + "\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setState(s: HoloView.State) { runOnUiThread { holo.state = s } }

    private fun say(msg: String, speak: Boolean = true) {
        appendLog("HOLO: $msg")
        runOnUiThread {
            status.text = msg
            if (speak) speakOut(msg)
        }
    }

    // ---------- speaking (ElevenLabs if a key is saved, otherwise the phone's own voice) ----------

    private fun speakOut(msg: String) {
        val text = msg.take(400)
        speaking = true
        stopListening()
        holo.state = HoloView.State.SPEAK
        if (Store.elevenKey.isNotBlank()) {
            Thread {
                val f = try { Eleven.synth(cacheDir, text) } catch (e: Exception) { null }
                runOnUiThread { if (f != null) playFile(f) else speakAndroid(text) }
            }.start()
        } else {
            speakAndroid(text)
        }
    }

    private fun speakAndroid(t: String) {
        val engine = tts
        if (engine == null) finishedSpeaking()
        else engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "holo")
    }

    private fun playFile(f: File) {
        try {
            player?.release()
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.path)
            mp.setOnCompletionListener { f.delete(); finishedSpeaking() }
            mp.setOnErrorListener { _, _, _ -> finishedSpeaking(); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            finishedSpeaking()
        }
    }

    private fun finishedSpeaking() {
        speaking = false
        holo.state = HoloView.State.IDLE
        ui.postDelayed({ startListening() }, 400)
    }

    // ---------- hands-free listening ----------

    private fun stopListening() {
        ui.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListening() {
        if (!Store.handsFree || speaking || !resumed) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "No speech recognizer on this phone. Type instead."
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                if (!speaking) { holo.state = HoloView.State.LISTEN; status.text = "Listening..." }
            }
            override fun onResults(b: Bundle?) {
                if (!speaking && !busy.get()) holo.state = HoloView.State.IDLE
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) handle(t)
                ui.postDelayed({ startListening() }, 300)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
                if (!speaking && !busy.get()) holo.state = HoloView.State.IDLE
                val wait = if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 300L else 2000L
                ui.postDelayed({ startListening() }, wait)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        )
    }

    // ---------- commands ----------

    private fun captureKey(t: String): Boolean {
        val s = t.trim()
        if (Regex("""^AIza[0-9A-Za-z_-]{30,}$""").matches(s)) {
            Store.apiKey = s
            appendLog("HOLO: Gemini key saved (hidden).")
            status.text = "Gemini key saved."
            return true
        }
        if (Regex("""^sk_[0-9A-Za-z]{20,}$""").matches(s)) {
            Store.elevenKey = s
            appendLog("HOLO: ElevenLabs key saved (hidden).")
            status.text = "ElevenLabs key saved."
            return true
        }
        return false
    }

    private fun handle(raw: String) {
        if (captureKey(raw)) return
        val text = raw.trim()
        if (text.isEmpty()) return
        appendLog("YOU: $text")
        val l = text.lowercase()

        if (l == "stop" || l == "stop it" || l == "cancel") {
            Agent.cancelled = true; say("Stopped."); return
        }
        if (l.contains("change to free talk mode")) {
            Store.freeMode = true; runOnUiThread { refreshMode() }
            say("Free talk mode on. Try to keep up."); return
        }
        if (l.contains("change to normal")) {
            Store.freeMode = false; runOnUiThread { refreshMode() }
            say("Back to normal mode. Boring, but fine."); return
        }

        val add = Regex("^(?:add a task|add task|new task|note down|note|remind me to|task)[ :,-]+(.+)$",
            RegexOption.IGNORE_CASE).find(text)
        if (add != null) {
            val t = add.groupValues[1].trim()
            Store.addTask(t); runOnUiThread { refreshTasks() }
            say("Noted: $t"); return
        }
        if (Regex("^(show|list|read)( my)? tasks$|^my tasks$").matches(l)) {
            val t = Store.tasks()
            runOnUiThread { tasksPanel.visibility = android.view.View.VISIBLE }
            say(if (t.isEmpty()) "You have no tasks." else "You have ${t.size} tasks: " + t.joinToString(". "))
            return
        }
        if (l == "clear tasks" || l == "clear my tasks") {
            Store.clearTasks(); runOnUiThread { refreshTasks() }
            say("All tasks cleared."); return
        }

        if (!busy.compareAndSet(false, true)) { say("Hold on, still working on the last one.", false); return }
        setState(HoloView.State.THINK)
        bg.execute {
            val reply = try {
                if (Store.freeMode) Chat.reply(text)
                else Agent.run(text) { s -> runOnUiThread { status.text = s } }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            busy.set(false)
            setState(HoloView.State.IDLE)
            say(reply)
        }
    }

    private fun askKeys() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        // Keys are hidden (dots) and never shown again once saved.
        fun secret(h: String, saved: Boolean) = EditText(this).apply {
            hint = if (saved) "$h (saved, type to replace)" else h
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val g = secret("Gemini API key", Store.apiKey.isNotBlank())
        val e = secret("ElevenLabs API key (optional)", Store.elevenKey.isNotBlank())
        val v = EditText(this).apply {
            hint = "ElevenLabs voice ID (optional)"; setText(Store.elevenVoice); isSingleLine = true
        }
        box.addView(g); box.addView(e); box.addView(v)
        AlertDialog.Builder(this)
            .setTitle("Keys")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                if (g.text.isNotBlank()) Store.apiKey = g.text.toString()
                if (e.text.isNotBlank()) Store.elevenKey = e.text.toString()
                Store.elevenVoice = v.text.toString()
                say("Saved.", false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshMode()
        refreshMic()
        if (AgentService.instance == null) status.text = "Phone control is OFF. Tap ACCESS and enable Holo."
        startListening()
    }

    override fun onPause() {
        resumed = false
        stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        player?.release()
        stopListening()
        super.onDestroy()
    }
}
