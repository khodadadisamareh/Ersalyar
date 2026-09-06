package ir.ersalyar.app

import android.content.Context
import org.json.JSONObject

object ScheduleStore {
    private const val PREF = "schedule_store"
    fun save(context: Context, s: Schedule) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(s.id.toString(), JSONObject().apply {
                put("content", s.content); put("imageUri", s.imageUri ?: ""); put("channel", s.channel); put("groupName", s.groupName); put("groupNames", org.json.JSONArray(s.groupNames)); put("recipientNames", org.json.JSONArray(s.recipientNames)); put("imageUris", org.json.JSONArray(s.imageUris))
                put("scheduleTime", s.scheduleTime); put("scheduleType", s.scheduleType); put("weekdays", s.weekdays); put("status", s.status)
            }.toString()).apply()
    }
    fun remove(context: Context, id: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(id.toString()).apply()
    }
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
    fun load(context: Context, id: Int): Schedule? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(id.toString(), null) ?: return null
        val j = JSONObject(raw)
        val arr=j.optJSONArray("groupNames"); val groups=mutableListOf<String>(); if(arr!=null) for(i in 0 until arr.length()) groups.add(arr.getString(i)); if(groups.isEmpty()) groups.add(j.getString("groupName"));
        val rarr=j.optJSONArray("recipientNames"); val recipients=mutableListOf<String>(); if(rarr!=null) for(i in 0 until rarr.length()) recipients.add(rarr.getString(i));
        return Schedule(id, j.getString("content"), j.optString("imageUri", "").ifBlank { null }, j.optJSONArray("imageUris").let { a -> if (a == null) emptyList() else List(a.length()) { i -> a.getString(i) } }, j.getString("channel"), groups.first(), groups, groups.size, recipients, recipients.size, j.getString("scheduleType"), j.getString("scheduleTime"), j.optString("weekdays"), j.optString("status", "active"))
    }
}
