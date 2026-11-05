package com.holo.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Original holographic AI core drawn in real 3D math (rotation + perspective):
 * a wireframe sphere, tilted orbit rings, hologram base, scanlines and drifting particles.
 * Drag with a finger to spin it. It reacts to the assistant state.
 */
class HoloView(ctx: Context) : View(ctx) {

    enum class State { IDLE, LISTEN, THINK, SPEAK }

    @Volatile var state = State.IDLE

    private class Line(val pts: FloatArray, val spin: Float)

    private val lines = ArrayList<Line>()
    private var yaw = 0f
    private var pitch = 0.35f
    private var t = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val sx = FloatArray(128)
    private val sy = FloatArray(128)
    private val sz = FloatArray(128)
    private val backPath = Path()
    private val frontPath = Path()

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
        val r = min(fw, fh) * 0.30f
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

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.38f
        val r = min(w, h) * 0.30f

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

        // hologram base and beam
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

        // glowing core
        c.save()
        c.translate(cx, cy)
        c.drawCircle(0f, 0f, r * 0.6f * pulse, coreP)
        c.restore()

        // drifting particles
        for (i in px.indices) {
            py[i] -= ps[i]
            if (py[i] < 0f) { py[i] = 1f; px[i] = Random.nextFloat() }
            c.drawCircle(px[i] * w, py[i] * h, 2f, dotP)
        }

        // scanlines and sweeping band
        c.drawLines(scanLines, scanP)
        val by = (t * 18f) % h
        c.drawLine(0f, by, w, by, bandP)

        postInvalidateOnAnimation()
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
