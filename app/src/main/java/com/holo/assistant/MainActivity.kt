package com.holo.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val bg = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)

        tts = TextToSpeech(this) { }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { holo.state = HoloView.State.SPEAK }
            override fun onDone(id: String?) { holo.state = HoloView.State.IDLE }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { holo.state = HoloView.State.IDLE }
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
        refreshTasks()

        if (Store.apiKey.isBlank()) {
            say("Welcome. Tap KEY to add your Gemini key, then ACCESS to switch on phone control.", false)
        } else {
            say("Online. Tap the mic or type a command.", false)
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

        // top bar
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        modeLabel = TextView(this).apply { setTextColor(ACCENT); textSize = 13f; letterSpacing = 0.15f }
        top.addView(modeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("KEY") { askKey() })
        top.addView(btn("ACCESS") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        top.addView(btn("TASKS") {
            tasksPanel.visibility = if (tasksPanel.visibility == android.view.View.VISIBLE)
                android.view.View.GONE else android.view.View.VISIBLE
        })
        top.addView(btn("STOP") { Agent.cancelled = true; say("Stopped.", false) })
        col.addView(top)

        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(0, dp(8), 0, 0) }
        col.addView(status)

        // tasks panel
        tasksPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt()); setStroke(dp(1), ACCENT); cornerRadius = dp(8).toFloat()
            }
        }
        col.addView(tasksPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // spacer: the hologram shows through here
        col.addView(android.view.View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        // log
        log = TextView(this).apply { setTextColor(0xFFFFB3AA.toInt()); textSize = 13f }
        logScroll = ScrollView(this).apply {
            addView(log)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        col.addView(logScroll, LinearLayout.LayoutParams(-1, dp(140)))

        // input row
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Command, or: add task ..."
            setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 14f; maxLines = 2
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("SEND") { val t = input.text.toString(); input.setText(""); handle(t) })
        row.addView(btn("MIC") { listen() })
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
        runOnUiThread { status.text = msg }
        if (speak) tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "holo")
    }

    private fun handle(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        appendLog("YOU: $text")
        val l = text.lowercase()

        // voice mode switches
        if (l.contains("change to free talk mode")) {
            Store.freeMode = true; runOnUiThread { refreshMode() }
            say("Free talk mode on. Try to keep up."); return
        }
        if (l.contains("change to normal")) {
            Store.freeMode = false; runOnUiThread { refreshMode() }
            say("Back to normal mode. Boring, but fine."); return
        }

        // tasks
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

    private fun askKey() {
        val e = EditText(this).apply { setText(Store.apiKey); hint = "Gemini API key" }
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setView(e)
            .setPositiveButton("Save") { _, _ -> Store.apiKey = e.text.toString(); say("Key saved.", false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("No speech recognizer on this phone. Type instead.", false)
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { holo.state = HoloView.State.LISTEN; status.text = "Listening..." }
            override fun onResults(b: Bundle?) {
                holo.state = HoloView.State.IDLE
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) handle(t)
            }
            override fun onError(e: Int) { holo.state = HoloView.State.IDLE; status.text = "Didn't catch that." }
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
        )
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
    }

    override fun onResume() {
        super.onResume()
        refreshMode()
        if (AgentService.instance == null) status.text = "Phone control is OFF. Tap ACCESS and enable Holo."
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
