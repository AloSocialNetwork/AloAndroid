package com.alo.android

import org.json.JSONObject
import java.time.Instant

/**
 * «Иван вступил(а) в группу», «Анна добавила Олега», «Пётр покинул(а) группу»: the joins and leaves a group shows
 * among its messages, as Telegram does. The server keeps them as group events
 * (`GET /api/group-chats/:id/service-events`); the site and the iPhone app word them the same way.
 */
data class AloGroupServiceEvent(
    val id: Int,
    val action: String,
    val createdAt: String,
    val actorId: Int,
    val actorName: String,
    val targetId: Int,
    val targetName: String
) {
    fun text(meId: Int): String {
        fun name(id: Int, value: String) = if (id == meId) "Вы" else value.ifBlank { "Участник" }
        val selfAction = actorId == targetId
        val youActor = actorId == meId
        val targetIsMe = targetId == meId
        val joined = if (targetIsMe) "Вы вступили в группу" else "${name(targetId, targetName)} вступил(а) в группу"
        val left = if (targetIsMe) "Вы покинули группу" else "${name(targetId, targetName)} покинул(а) группу"
        val target = if (targetIsMe) "вас" else name(targetId, targetName)
        return when (action) {
            "member_joined" -> joined
            "member_left" -> left
            "member_added" -> if (selfAction) joined else "${name(actorId, actorName)} ${if (youActor) "добавили" else "добавил(а)"} $target"
            "member_removed" -> if (selfAction) left else "${name(actorId, actorName)} ${if (youActor) "удалили" else "удалил(а)"} $target"
            else -> ""
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AloGroupServiceEvent {
            val actor = json.optJSONObject("actor")
            val target = json.optJSONObject("target")
            return AloGroupServiceEvent(
                id = json.optInt("id"),
                action = json.optString("action"),
                createdAt = json.optString("createdAt"),
                actorId = actor?.optInt("id") ?: 0,
                actorName = actor?.optString("name").orEmpty(),
                targetId = target?.optInt("id") ?: 0,
                targetName = target?.optString("name").orEmpty()
            )
        }
    }
}

data class AloGroupServiceLine(val id: Int, val text: String)

/** Lines before each message (by message id) and after the last one. */
data class AloGroupServicePlacement(
    val before: Map<Int, List<AloGroupServiceLine>> = emptyMap(),
    val after: List<AloGroupServiceLine> = emptyList()
)

object AloGroupServiceLines {
    /** Set by MainActivity: reads a group's events from the server. */
    @Volatile
    var loader: (suspend (roomId: Int) -> List<AloGroupServiceEvent>)? = null

    private fun parse(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    /**
     * Every event goes before the first message written after it, the newest ones after the last message.
     * Events older than the loaded part of the history are left out while older messages can still load.
     */
    fun place(events: List<AloGroupServiceEvent>, messages: List<AloChatMessage>, hasOlder: Boolean, meId: Int): AloGroupServicePlacement {
        if (events.isEmpty()) return AloGroupServicePlacement()
        val dated = messages.map { it.id to parse(it.createdAt) }
        val oldest = if (hasOlder) dated.firstOrNull()?.second else null
        val before = linkedMapOf<Int, MutableList<AloGroupServiceLine>>()
        val after = mutableListOf<AloGroupServiceLine>()
        for (event in events) {
            val text = event.text(meId)
            val time = parse(event.createdAt)
            if (text.isEmpty() || time == null) continue
            if (oldest != null && time.isBefore(oldest)) continue
            val next = dated.firstOrNull { (it.second ?: Instant.MIN) >= time }
            if (next != null) before.getOrPut(next.first) { mutableListOf() } += AloGroupServiceLine(event.id, text)
            else after += AloGroupServiceLine(event.id, text)
        }
        return AloGroupServicePlacement(before, after)
    }
}
