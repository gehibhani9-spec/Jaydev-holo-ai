package com.holo.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val RF = 0.22f

/**
 * Original holographic HUD: a 3D wireframe core in the middle (real rotation + perspective),
 * a radar panel on the left, a power-core panel on the right, tick ring, scanlines, particles.
 * Drag the middle to spin it.
 */
class HoloView(ctx: Context) : View(ctx) {

    enum class State { IDLE, LISTEN, THINK, SPEAK }

    @Volatile var state = State.IDLE
    @Volatile var modeText = "NORMAL"

    private class Line(val pts: FloatArray, val spin: Float)

    private val lines = ArrayList<Line>()
    private var yaw = 0f
    private var pitch = 0.35f
    private var t = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var frame = 0
    private var battery = -1
    private var clock = "--:--"
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val d = resources.displayMetrics.density

    private val cyan = Color.rgb(90, 210, 255)
    private val green = Color.rgb(70, 255, 130)

    private val sx = FloatArray(128)
    private val sy = FloatArray(128)
    private val sz = FloatArray(128)
    private val backPath = Path()
    private val frontPath = Path()
    private val hex = Path()

    private fun stroke(a: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w
        color = Color.argb(a, 255, 80, 60)
        strokeCap = Paint.Cap.ROUND
    }

    private val backP = stroke(60, 2f)
    private val frontP = stroke(230, 3f)
    private val glowP = stroke(55, 10f)
    private val baseP = stroke(120, 2f)
    private val scanP = stroke(14, 1f)
    private val bandP = stroke(40, 3f)
    private val hudP = stroke(200, 2f)
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 150, 90) }
    private val coreP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPath = Path()

    private var scanLines = FloatArray(0)
    private val px = FloatArray(50) { Random.nextFloat() }
    private val py = FloatArray(50) { Random.nextFloat() }
    private val ps = FloatArray(50) { 0.0006f + Random.nextFloat() * 0.0016f }

    init {
        for (lat in intArrayOf(-60, -30, 0, 30, 60)) {
            val a = Math.toRadians(lat.toDouble())
            lines.add(circle(cos(a).toFloat(), sin(a).toFloat(), 0f, 64, 1f))
        }
        for (k in 0 until 6) {
            val phi = PI.toFloat() * k / 6f
            val n = 64
            val pts = FloatArray((n + 1) * 3)
            for (i in 0..n) {
                val a = 2f * PI.toFloat() * i / n
                pts[i * 3] = cos(a) * cos(phi)
                pts[i * 3 + 1] = sin(a)
                pts[i * 3 + 2] = cos(a) * sin(phi)
            }
            lines.add(Line(pts, 1f))
        }
        lines.add(circle(1.35f, 0f, 0.5f, 80, -1.6f))
        lines.add(circle(1.6f, 0f, -0.9f, 90, 2.2f))
        lines.add(circle(1.85f, 0f, 1.4f, 100, 1.1f))
    }

    private fun circle(radius: Float, y: Float, tilt: Float, n: Int, spin: Float): Line {
        val pts = FloatArray((n + 1) * 3)
        val ct = cos(tilt)
        val st = sin(tilt)
        for (i in 0..n) {
            val a = 2f * PI.toFloat() * i / n
            val x = radius * cos(a)
            val z = radius * sin(a)
            pts[i * 3] = x * ct - y * st
            pts[i * 3 + 1] = x * st + y * ct
            pts[i * 3 + 2] = z
        }
        return Line(pts, spin)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        val r = min(fw, fh) * RF
        coreP.shader = RadialGradient(0f, 0f, r * 0.6f,
            intArrayOf(Color.argb(255, 255, 220, 180), Color.argb(150, 255, 70, 50), Color.argb(0, 255, 40, 30)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        haloP.shader = RadialGradient(0f, 0f, r * 2.4f,
            intArrayOf(Color.argb(70, 255, 50, 40), Color.argb(0, 255, 50, 40)),
            null, Shader.TileMode.CLAMP)
        val cy = fh * 0.38f
        val baseY = cy + r * 1.55f
        beamP.shader = LinearGradient(0f, cy, 0f, baseY,
            Color.argb(0, 255, 60, 40), Color.argb(70, 255, 60, 40), Shader.TileMode.CLAMP)
        beamPath.reset()
        beamPath.moveTo(fw / 2 - r * 0.9f, cy)
        beamPath.lineTo(fw / 2 + r * 0.9f, cy)
        beamPath.lineTo(fw / 2 + r * 1.5f, baseY)
        beamPath.lineTo(fw / 2 - r * 1.5f, baseY)
        beamPath.close()
        val n = (h / 6) + 1
        scanLines = FloatArray(n * 4)
        for (i in 0 until n) {
            scanLines[i * 4] = 0f
            scanLines[i * 4 + 1] = i * 6f
            scanLines[i * 4 + 2] = fw
            scanLines[i * 4 + 3] = i * 6f
        }
    }

    private fun updateBattery() {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.38f
        val r = min(w, h) * RF

        frame++
        if (frame % 30 == 1) clock = fmt.format(Date())
        if (frame % 300 == 1) updateBattery()

        val speed = when (state) {
            State.IDLE -> 0.006f
            State.LISTEN -> 0.012f
            State.THINK -> 0.03f
            State.SPEAK -> 0.015f
        }
        val amp = when (state) {
            State.IDLE -> 0.02f
            State.LISTEN -> 0.05f
            State.THINK -> 0.03f
            State.SPEAK -> 0.07f
        }
        yaw += speed
        t += 0.05f
        val pulse = 1f + amp * sin(t * if (state == State.THINK) 4f else 2f)

        c.drawColor(Color.rgb(6, 2, 4))

        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, r * 2.4f, haloP)
        c.restore()

        tickRing(c, cx, cy, r * 2.15f)

        c.drawPath(beamPath, beamP)
        val baseY = cy + r * 1.55f
        for (k in 0 until 3) {
            val rr = r * (1.0f + 0.35f * k)
            c.drawOval(cx - rr, baseY - rr * 0.18f, cx + rr, baseY + rr * 0.18f, baseP)
        }

        val cp = cos(pitch)
        val sp = sin(pitch)
        backPath.reset()
        frontPath.reset()

        for (ln in lines) {
            val n = ln.pts.size / 3
            val ang = yaw * ln.spin
            val cY = cos(ang)
            val sY = sin(ang)
            for (i in 0 until n) {
                val x = ln.pts[i * 3]
                val y = ln.pts[i * 3 + 1]
                val z = ln.pts[i * 3 + 2]
                val x1 = x * cY + z * sY
                val z1 = -x * sY + z * cY
                val y1 = y * cp - z1 * sp
                val z2 = y * sp + z1 * cp
                val persp = 1f / (1f - 0.25f * z2)
                sx[i] = cx + x1 * r * pulse * persp
                sy[i] = cy - y1 * r * pulse * persp
                sz[i] = z2
            }
            for (i in 0 until n - 1) {
                val path = if ((sz[i] + sz[i + 1]) > 0f) frontPath else backPath
                path.moveTo(sx[i], sy[i])
                path.lineTo(sx[i + 1], sy[i + 1])
            }
        }

        c.drawPath(backPath, backP)
        c.drawPath(frontPath, glowP)
        frontP.alpha = 200 + Random.nextInt(40)
        c.drawPath(frontPath, frontP)

        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, r * 0.6f * pulse, coreP)
        c.restore()

        for (i in px.indices) {
            py[i] -= ps[i]
            if (py[i] < 0f) { py[i] = 1f; px[i] = Random.nextFloat() }
            c.drawCircle(px[i] * w, py[i] * h, 2f, dotP)
        }

        // side panels
        val y0 = cy - r * 1.7f
        val y1 = cy + r * 1.8f
        leftPanel(c, w, y0, y1)
        rightPanel(c, w, y0, y1)
        screenBrackets(c, w, h)

        c.drawLines(scanLines, scanP)
        val by = (t * 18f) % h
        c.drawLine(0f, by, w, by, bandP)

        postInvalidateOnAnimation()
    }

    // ---------- HUD pieces ----------

    private fun tickRing(c: Canvas, cx: Float, cy: Float, r: Float) {
        hudP.color = Color.argb(90, 255, 90, 70)
        hudP.strokeWidth = 2f
        val n = 90
        for (i in 0 until n) {
            val a = i * 2f * PI.toFloat() / n + yaw * 0.3f
            val len = if (i % 5 == 0) r * 0.05f else r * 0.025f
            val ca = cos(a)
            val sa = sin(a)
            c.drawLine(cx + ca * r, cy + sa * r, cx + ca * (r + len), cy + sa * (r + len), hudP)
        }
        hudP.color = Color.argb(200, 255, 120, 80)
        hudP.strokeWidth = 4f
        val rr = r * 0.97f
        val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        val deg = Math.toDegrees((yaw * 1.5f).toDouble()).toFloat()
        for (k in 0 until 3) c.drawArc(rect, deg + k * 120f, 40f, false, hudP)
    }

    private fun panelFrame(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, col: Int) {
        fillP.color = Color.argb(150, 4, 2, 8)
        c.drawRect(x0, y0, x1, y1, fillP)
        hudP.color = Color.argb(110, Color.red(col), Color.green(col), Color.blue(col))
        hudP.strokeWidth = 2f
        c.drawRect(x0, y0, x1, y1, hudP)
        val b = 14f * d
        hudP.color = col
        hudP.strokeWidth = 4f
        c.drawLine(x0, y0, x0 + b, y0, hudP); c.drawLine(x0, y0, x0, y0 + b, hudP)
        c.drawLine(x1, y0, x1 - b, y0, hudP); c.drawLine(x1, y0, x1, y0 + b, hudP)
        c.drawLine(x0, y1, x0 + b, y1, hudP); c.drawLine(x0, y1, x0, y1 - b, hudP)
        c.drawLine(x1, y1, x1 - b, y1, hudP); c.drawLine(x1, y1, x1, y1 - b, hudP)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, col: Int, size: Float = 8f) {
        textP.color = col
        textP.textSize = size * d
        c.drawText(s, x, y, textP)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, frac: Float, col: Int, name: String) {
        label(c, name, x, y, col)
        val by = y + 4f * d
        val bh = 6f * d
        hudP.color = Color.argb(130, Color.red(col), Color.green(col), Color.blue(col))
        hudP.strokeWidth = 1.5f
        c.drawRect(x, by, x + w, by + bh, hudP)
        fillP.color = Color.argb(210, Color.red(col), Color.green(col), Color.blue(col))
        c.drawRect(x, by, x + w * frac.coerceIn(0f, 1f), by + bh, fillP)
    }

    private fun radar(c: Canvas, cx: Float, cy: Float, r: Float) {
        hudP.color = Color.argb(180, 90, 210, 255)
        hudP.strokeWidth = 1.5f
        for (k in 1..3) {
            val rr = r * k / 3f
            hex.reset()
            for (i in 0..6) {
                val a = i * PI.toFloat() / 3f
                val x = cx + rr * cos(a)
                val y = cy + rr * sin(a)
                if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
            }
            c.drawPath(hex, hudP)
        }
        for (i in 0 until 6) {
            val a = i * PI.toFloat() / 3f
            c.drawLine(cx, cy, cx + r * cos(a), cy + r * sin(a), hudP)
        }
        val deg = (t * 30f) % 360f
        fillP.color = Color.argb(70, 90, 210, 255)
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), deg - 40f, 40f, true, fillP)
        hudP.strokeWidth = 2.5f
        hudP.color = Color.argb(255, 140, 230, 255)
        val a = Math.toRadians(deg.toDouble()).toFloat()
        c.drawLine(cx, cy, cx + r * cos(a), cy + r * sin(a), hudP)
        fillP.color = Color.argb(220, 255, 255, 255)
        c.drawCircle(cx + r * 0.5f, cy - r * 0.3f, 2.5f * d, fillP)
        c.drawCircle(cx - r * 0.4f, cy + r * 0.45f, 2.5f * d, fillP)
        c.drawCircle(cx + r * 0.1f, cy + r * 0.7f, 2.5f * d, fillP)
    }

    private fun powerCore(c: Canvas, cx: Float, cy: Float, r: Float) {
        hudP.strokeWidth = 2.5f
        hudP.color = Color.argb(210, 70, 255, 130)
        for (k in 1..3) {
            val rr = r * k / 3f
            val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            val base = t * 40f * (if (k % 2 == 0) -1f else 1f) + k * 50f
            for (s in 0 until 3) c.drawArc(rect, base + s * 120f, 70f, false, hudP)
        }
        val pulse = 1f + 0.15f * sin(t * 3f)
        fillP.color = Color.argb(60, 70, 255, 130)
        c.drawCircle(cx, cy, r * 0.5f * pulse, fillP)
        fillP.color = Color.argb(235, 150, 255, 190)
        c.drawCircle(cx, cy, r * 0.22f * pulse, fillP)
    }

    private fun leftPanel(c: Canvas, w: Float, y0: Float, y1: Float) {
        val x0 = 4f * d
        val x1 = w * 0.17f
        panelFrame(c, x0, y0, x1, y1, cyan)
        val pad = 8f * d
        label(c, "SENSOR", x0 + pad, y0 + pad + 9f * d, cyan)
        val er = (x1 - x0) * 0.36f
        val ecx = (x0 + x1) / 2f
        val ecy = y0 + pad + 16f * d + er
        radar(c, ecx, ecy, er)
        var y = ecy + er + 18f * d
        val bw = x1 - x0 - 2 * pad
        bar(c, x0 + pad, y, bw, 0.7f + 0.2f * sin(t * 0.7f), cyan, "NET")
        y += 24f * d
        val mic = if (state == State.LISTEN) 0.3f + 0.6f * abs(sin(t * 5f)) else 0.08f
        bar(c, x0 + pad, y, bw, mic, cyan, "MIC")
        y += 24f * d
        val ai = if (state == State.THINK || state == State.SPEAK) 0.3f + 0.6f * abs(sin(t * 3.3f)) else 0.12f
        bar(c, x0 + pad, y, bw, ai, cyan, "AI")
    }

    private fun rightPanel(c: Canvas, w: Float, y0: Float, y1: Float) {
        val x1 = w - 4f * d
        val x0 = w - w * 0.17f
        panelFrame(c, x0, y0, x1, y1, green)
        val pad = 8f * d
        label(c, "POWER", x0 + pad, y0 + pad + 9f * d, green)
        val er = (x1 - x0) * 0.36f
        val ecx = (x0 + x1) / 2f
        val ecy = y0 + pad + 16f * d + er
        powerCore(c, ecx, ecy, er)
        var y = ecy + er + 18f * d
        val bw = x1 - x0 - 2 * pad
        val bat = if (battery >= 0) "BAT $battery%" else "BAT --"
        bar(c, x0 + pad, y, bw, if (battery >= 0) battery / 100f else 0f, green, bat)
        y += 36f * d
        label(c, clock, x0 + pad, y, Color.WHITE, 15f)
        y += 18f * d
        label(c, "MODE", x0 + pad, y, green)
        y += 12f * d
        label(c, modeText, x0 + pad, y, Color.WHITE)
        y += 16f * d
        label(c, state.name, x0 + pad, y, green)
    }

    private fun screenBrackets(c: Canvas, w: Float, h: Float) {
        val m = 6f * d
        val b = 26f * d
        hudP.color = Color.argb(150, 255, 90, 70)
        hudP.strokeWidth = 3f
        c.drawLine(m, m, m + b, m, hudP); c.drawLine(m, m, m, m + b, hudP)
        c.drawLine(w - m, m, w - m - b, m, hudP); c.drawLine(w - m, m, w - m, m + b, hudP)
        c.drawLine(m, h - m, m + b, h - m, hudP); c.drawLine(m, h - m, m, h - m - b, hudP)
        c.drawLine(w - m, h - m, w - m - b, h - m, hudP); c.drawLine(w - m, h - m, w - m, h - m - b, hudP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                yaw += (e.x - lastX) * 0.01f
                pitch = (pitch + (e.y - lastY) * 0.008f).coerceIn(-1.2f, 1.2f)
                lastX = e.x
                lastY = e.y
            }
        }
        return true
    }
}
