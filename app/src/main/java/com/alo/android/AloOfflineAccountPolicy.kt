package com.alo.android

import org.json.JSONObject

/** Offline data needs a known account AND the saved session currently installed in the client. */
internal object AloOfflineAccountPolicy {
    fun ownerForSession(selectedOwnerId: Int, savedCookieHeader: String?, currentCookieHeader: String): Int {
        if (selectedOwnerId <= 0) return 0
        return if (matchesSession(savedCookieHeader, currentCookieHeader)) selectedOwnerId else 0
    }

    fun matchesSession(savedCookieHeader: String?, currentCookieHeader: String): Boolean {
        val saved = cookies(savedCookieHeader.orEmpty()) ?: return false
        val current = cookies(currentCookieHeader) ?: return false
        return saved == current
    }

    fun bootstrapForOwner(snapshot: JSONObject, expectedOwnerId: Int): JSONObject? {
        if (expectedOwnerId <= 0) return null
        val actualOwnerId = snapshot.optJSONObject("data")?.optJSONObject("me")?.optInt("id", 0) ?: 0
        return snapshot.takeIf { actualOwnerId == expectedOwnerId }
    }

    private fun cookies(header: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        for (part in header.split(';')) {
            val entry = part.trim()
            if (entry.isEmpty()) continue
            val separator = entry.indexOf('=')
            if (separator <= 0) return null
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty() || result.put(name, value) != null) return null
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
