package com.holo.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("holo", Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) { sp.edit().putString("key", v.trim()).apply() }

    var elevenKey: String
        get() = sp.getString("ekey", "") ?: ""
        set(v) { sp.edit().putString("ekey", v.trim()).apply() }

    var elevenVoice: String
        get() = sp.getString("evoice", "") ?: ""
        set(v) { sp.edit().putString("evoice", v.trim()).apply() }

    var handsFree: Boolean
        get() = sp.getBoolean("hands", true)
        set(v) { sp.edit().putBoolean("hands", v).apply() }

    var freeMode: Boolean
        get() = sp.getBoolean("free", false)
        set(v) { sp.edit().putBoolean("free", v).apply() }

    fun tasks(): MutableList<String> {
        val a = JSONArray(sp.getString("tasks", "[]"))
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun saveTasks(l: List<String>) {
        sp.edit().putString("tasks", JSONArray(l).toString()).apply()
    }

    fun addTask(t: String) { val l = tasks(); l.add(t); saveTasks(l) }
    fun removeTask(i: Int) { val l = tasks(); if (i in l.indices) { l.removeAt(i); saveTasks(l) } }
    fun clearTasks() = saveTasks(emptyList())

    // Free talk chat history (saved on the phone, newest 200 messages)
    fun history(): JSONArray = JSONArray(sp.getString("chat", "[]"))

    fun addChat(role: String, text: String) {
        val a = history()
        a.put(JSONObject().put("role", role).put("text", text))
        val out = JSONArray()
        for (i in maxOf(0, a.length() - 200) until a.length()) out.put(a.get(i))
        sp.edit().putString("chat", out.toString()).apply()
    }
}
