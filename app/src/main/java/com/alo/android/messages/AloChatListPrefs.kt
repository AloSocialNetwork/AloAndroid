package com.alo.android

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pinned and archived chats per account (iOS's AloChatListPrefs).
 *
 * Pins belong to the account, as in Telegram: the server keeps their order (`/api/chat/pins`) and this
 * list is the device's copy of it, replaced by what the server sends. The archive stays on the device.
 */
internal class AloChatListPrefs(context: Context, fileName: String = "alo.chatlist.prefs.v1") {
    data class State(val pinned: List<String> = emptyList(), val archived: List<String> = emptyList())

    companion object {
        const val MAXIMUM_PINNED = 5

        fun key(conversation: AloConversationSummary): String =
            "${conversation.kind}:${if (conversation.kind == "direct") conversation.peerId else conversation.roomId}"
    }

    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    fun state(owner: Int): State {
        val json = prefs.getString(owner.toString(), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return State()
        fun list(name: String) = json.optJSONArray(name)?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return State(pinned = list("pinned"), archived = list("archived"))
    }

    /** Returns false when the pin limit would be exceeded. */
    fun setPinned(owner: Int, keys: List<String>, pinned: Boolean): Boolean {
        val state = state(owner)
        val next = if (pinned) {
            val fresh = keys.filter { it !in state.pinned }
            if (state.pinned.size + fresh.size > MAXIMUM_PINNED) return false
            // A new pin goes on top, as in Telegram.
            State(pinned = fresh + state.pinned, archived = state.archived - keys.toSet())
        } else {
            state.copy(pinned = state.pinned - keys.toSet())
        }
        save(owner, next)
        return true
    }

    fun setArchived(owner: Int, keys: List<String>, archived: Boolean) {
        val state = state(owner)
        val next = if (archived) {
            State(pinned = state.pinned - keys.toSet(), archived = state.archived + keys.filter { it !in state.archived })
        } else {
            state.copy(archived = state.archived - keys.toSet())
        }
        save(owner, next)
    }

    /** The account's pins as the server sent them; a pinned chat leaves the archive. */
    fun replacePinned(owner: Int, pinned: List<String>) {
        val state = state(owner)
        if (state.pinned == pinned) return
        save(owner, State(pinned = pinned, archived = state.archived - pinned.toSet()))
    }

    /** Pins made before the server kept them are sent once (`/api/chat/pins/import`); this remembers that it happened. */
    fun pinsImported(owner: Int): Boolean = prefs.getBoolean("pins_imported_$owner", false)

    fun markPinsImported(owner: Int) = prefs.edit().putBoolean("pins_imported_$owner", true).apply()

    /** UI fixtures only. */
    fun replace(owner: Int, pinned: List<String>, archived: List<String>) = save(owner, State(pinned, archived))

    private fun save(owner: Int, state: State) {
        prefs.edit().putString(
            owner.toString(),
            JSONObject().put("pinned", JSONArray(state.pinned)).put("archived", JSONArray(state.archived)).toString()
        ).apply()
    }
}

/**
 * Which chats have messages the user has not opened yet (iOS's AloUnreadTracker).
 *
 * The device remembers the newest message id it has shown per chat. A chat is unread when its newest
 * message is from someone else and newer than what was last seen. The first time a chat is seen it
 * counts as read, so installing the app does not light up every conversation.
 */
internal class AloUnreadTracker(context: Context, fileName: String = "alo.unread.v1") {
    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val seen: MutableMap<String, Int> = read("seen")
    /** Only chats actually opened here: hides a stale server count until the next list refresh. */
    private val opened: MutableMap<String, Int> = read("opened")

    private fun key(owner: Int, conversation: AloConversationSummary) =
        "$owner:${conversation.kind}:${if (conversation.kind == "direct") conversation.peerId else conversation.roomId}"

    /** Chats seen for the first time are treated as read. Returns true when anything changed. */
    fun establishBaseline(owner: Int, conversations: List<AloConversationSummary>): Boolean {
        var changed = false
        conversations.filter { it.lastMessageId > 0 }.forEach { conversation ->
            val id = key(owner, conversation)
            if (id !in seen) {
                seen[id] = conversation.lastMessageId
                changed = true
            }
        }
        if (changed) write("seen", seen)
        return changed
    }

    fun isUnread(owner: Int, conversation: AloConversationSummary): Boolean {
        if (conversation.lastMessageId <= 0 || conversation.lastSenderId == owner) return false
        val last = seen[key(owner, conversation)] ?: return false
        return conversation.lastMessageId > last
    }

    fun hasSeen(owner: Int, conversation: AloConversationSummary): Boolean {
        if (conversation.lastMessageId <= 0) return false
        val last = opened[key(owner, conversation)] ?: return false
        return last >= conversation.lastMessageId
    }

    fun markRead(owner: Int, conversation: AloConversationSummary) {
        if (conversation.lastMessageId <= 0) return
        val id = key(owner, conversation)
        if ((opened[id] ?: 0) < conversation.lastMessageId) {
            opened[id] = conversation.lastMessageId
            write("opened", opened)
        }
        if ((seen[id] ?: 0) < conversation.lastMessageId) {
            seen[id] = conversation.lastMessageId
            write("seen", seen)
        }
    }

    /** UI fixtures only: start from a known set of seen message ids. */
    fun replaceSeen(values: Map<String, Int>) {
        seen.clear()
        seen.putAll(values)
        write("seen", seen)
    }

    private fun read(name: String): MutableMap<String, Int> {
        val json = prefs.getString(name, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return mutableMapOf()
        return json.keys().asSequence().associateWith { json.optInt(it) }.toMutableMap()
    }

    private fun write(name: String, values: Map<String, Int>) {
        prefs.edit().putString(name, JSONObject(values as Map<*, *>).toString()).apply()
    }
}

/** What the chat list reads from the device stores; revisions in the app state trigger redraws. */
internal interface AloChatListModel {
    fun state(owner: Int): AloChatListPrefs.State
    fun isUnread(owner: Int, conversation: AloConversationSummary): Boolean
    /** The server's count, hidden once the chat was opened here and nothing new arrived. */
    fun unreadCount(owner: Int, conversation: AloConversationSummary): Int
}

internal val LocalAloChatListModel = staticCompositionLocalOf<AloChatListModel?> { null }

internal class AloDeviceChatListModel(
    private val prefs: AloChatListPrefs,
    private val tracker: AloUnreadTracker
) : AloChatListModel {
    override fun state(owner: Int) = prefs.state(owner)

    override fun unreadCount(owner: Int, conversation: AloConversationSummary): Int {
        if (!conversation.hasServerUnreadCount || conversation.unreadCount <= 0) return 0
        return if (tracker.hasSeen(owner, conversation)) 0 else conversation.unreadCount
    }

    override fun isUnread(owner: Int, conversation: AloConversationSummary): Boolean =
        unreadCount(owner, conversation) > 0 ||
            (!conversation.hasServerUnreadCount && tracker.isUnread(owner, conversation))
}

/** Pinned chats first in pin order, then the rest; archived chats only in the archive. */
internal fun arrangeConversations(
    all: List<AloConversationSummary>,
    state: AloChatListPrefs.State,
    archived: Boolean
): List<AloConversationSummary> {
    val archivedKeys = state.archived.toSet()
    if (archived) return all.filter { AloChatListPrefs.key(it) in archivedKeys }
    val visible = all.filter { AloChatListPrefs.key(it) !in archivedKeys }
    val pinned = state.pinned.mapNotNull { key -> visible.firstOrNull { AloChatListPrefs.key(it) == key } }
    val pinnedKeys = pinned.map(AloChatListPrefs::key).toSet()
    return pinned + visible.filter { AloChatListPrefs.key(it) !in pinnedKeys }
}
