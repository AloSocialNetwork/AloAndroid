package com.alo.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Tokens carry no target IDs. Only the authenticated resolver may choose a destination. */
internal class AloPendingPushOpenStore(private val preferences: SharedPreferences) {
    companion object {
        fun isValidChatTarget(peer: Int, room: Int): Boolean = peer > 0 && room >= 0 &&
            (if (room > 0) peer.toLong() == 1_000_000_000L + room else peer < 1_000_000_000)
    }
    @Synchronized
    fun capture(token: String?, version: String?) {
        if (version != "2" || token == null || !token.matches(Regex("[a-f0-9]{64}"))) return
        val records = records().filter { it.getString("token") != token }.toMutableList()
        records.add(JSONObject().put("token", token).put("created", System.currentTimeMillis()).put("rejected", JSONArray()))
        save(records.takeLast(20))
    }

    @Synchronized
    fun next(owner: Int): String? = records().firstOrNull { record ->
        val rejected = record.optJSONArray("rejected") ?: JSONArray()
        (0 until rejected.length()).none { rejected.optInt(it) == owner }
    }?.getString("token")

    @Synchronized
    fun complete(token: String) = save(records().filter { it.getString("token") != token })

    @Synchronized
    fun unavailableFor(token: String, owner: Int) = save(records().onEach { record ->
        if (record.getString("token") == token) record.getJSONArray("rejected").put(owner)
    })

    private fun records(): List<JSONObject> {
        val array = runCatching { JSONArray(preferences.getString("pending-push-opens-v2", "[]")) }.getOrElse { JSONArray() }
        val earliest = System.currentTimeMillis() - 90L * 86400_000
        return (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optLong("created") >= earliest }
    }
    private fun save(records: List<JSONObject>) {
        // Not commit(): on a full disk it returns false and blocks the main thread, and this runs on a tap.
        preferences.edit().putString("pending-push-opens-v2", JSONArray(records).toString()).apply()
    }
}
