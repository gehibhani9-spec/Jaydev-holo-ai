package com.holo.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** The "hands and eyes": reads the screen of whatever app is open and performs taps, typing, scrolling. */
class AgentService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentService? = null
    }

    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Text description of the current screen, with a number for each useful element. */
    fun snapshot(): String {
        val root = rootInActiveWindow ?: return "(screen not readable)"
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("APP: ${root.packageName}\n")

        fun walk(n: AccessibilityNodeInfo) {
            if (list.size >= 120) return
            val text = n.text?.toString()?.take(80) ?: ""
            val desc = n.contentDescription?.toString()?.take(80) ?: ""
            val useful = n.isVisibleToUser &&
                (n.isClickable || n.isEditable || n.isScrollable || text.isNotBlank() || desc.isNotBlank())
            if (useful) {
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("tap")
                if (n.isEditable) flags.add("input")
                if (n.isScrollable) flags.add("scroll")
                val kind = n.className?.toString()?.substringAfterLast('.') ?: "View"
                val label = if (text.isNotBlank()) text else desc
                sb.append("[${list.size}] $kind \"$label\" ${flags.joinToString(",")}\n")
                list.add(n)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        walk(root)
        nodes = list
        return sb.toString()
    }

    fun label(i: Int): String {
        val n = nodes.getOrNull(i) ?: return ""
        return (n.text ?: n.contentDescription)?.toString() ?: ""
    }

    fun tap(i: Int): Boolean {
        val n = nodes.getOrNull(i) ?: return false
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return gestureTap(r.exactCenterX(), r.exactCenterY())
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun typeText(i: Int, text: String): Boolean {
        val n = (if (i >= 0) nodes.getOrNull(i) else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
            ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun scroll(down: Boolean): Boolean {
        val n = nodes.firstOrNull { it.isScrollable } ?: return false
        return n.performAction(
            if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openApp(name: String): Boolean {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(q, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) } ?: return false
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }
}
