package ir.ersalyar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SendLog(val id: Long, val scheduleId: Int, val channel: String, val group: String, val content: String, val success: Boolean, val detail: String, val time: Long)

object SendLogStore {
    private const val PREF = "send_logs"
    private const val KEY = "items"
    fun add(context: Context, scheduleId: Int, channel: String, group: String, content: String, success: Boolean, detail: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val old = JSONArray(prefs.getString(KEY, "[]"))
        val arr = JSONArray()
        val item = JSONObject().apply { put("id", System.currentTimeMillis()); put("scheduleId", scheduleId); put("channel", channel); put("group", group); put("content", content); put("success", success); put("detail", detail); put("time", System.currentTimeMillis()) }
        arr.put(item)
        for (i in 0 until old.length()) if (i < 499) arr.put(old.getJSONObject(i))
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun list(context: Context): List<SendLog> {
        val arr = JSONArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"))
        val out = mutableListOf<SendLog>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            out.add(SendLog(j.getLong("id"), j.getInt("scheduleId"), j.getString("channel"), j.getString("group"), j.getString("content"), j.getBoolean("success"), j.getString("detail"), j.getLong("time")))
        }
        return out
    }
}
