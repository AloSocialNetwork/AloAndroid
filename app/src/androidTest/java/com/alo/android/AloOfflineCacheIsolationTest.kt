package com.alo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AloOfflineCacheIsolationTest {
    private lateinit var appContext: Context
    private lateinit var isolatedContext: Context
    private lateinit var root: File
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsName: String
    private lateinit var cache: AloOfflineCacheStore

    @Before fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        root = File(appContext.cacheDir, "offline-isolation-${UUID.randomUUID()}").apply { mkdirs() }
        isolatedContext = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = root
        }
        prefsName = "offline-isolation-${UUID.randomUUID()}"
        prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        cache = AloOfflineCacheStore(isolatedContext, prefs)
    }

    @After fun tearDown() {
        root.deleteRecursively()
        appContext.deleteSharedPreferences(prefsName)
    }

    @Test fun missingSelectedCacheDoesNotFallBackToThePreviousAccount() {
        cache.saveBootstrap(snapshot(41))
        assertNull(cache.readBootstrapRaw(42))
        assertNull(cache.readBootstrapRaw(0))
        assertEquals(41, owner(cache.readBootstrapRaw(41)))
    }

    @Test fun switchingToASavedAccountWithoutItsCacheNeverDisplaysThePreviousAccount() {
        val accounts = AloSecureAccountStore(isolatedContext)
        accounts.saveCurrentSession(user(41), "alo_session=fixture-a")
        cache.saveBootstrap(snapshot(41))
        accounts.saveCurrentSession(user(42), "alo_session=fixture-b")
        val selectedOwner = accounts.offlineOwnerForSession("alo_session=fixture-b")
        assertEquals(42, selectedOwner)
        assertNull(cache.readBootstrapRaw(selectedOwner))
        assertEquals(0, accounts.offlineOwnerForSession("alo_session=fixture-a"))
        assertEquals(0, accounts.offlineOwnerForSession("alo_session=new-unsaved-login"))
        cache.saveBootstrap(snapshot(42))
        cache.saveBootstrap(snapshot(41))
        assertEquals(42, owner(cache.readBootstrapRaw(accounts.offlineOwnerForSession("alo_session=fixture-b"))))
    }

    @Test fun eachAccountReadsItsOwnEncryptedSnapshotRegardlessOfTheLastWriter() {
        cache.saveBootstrap(snapshot(41))
        cache.saveBootstrap(snapshot(42))
        cache.saveBootstrap(snapshot(41)) // A late background write must not select A for B.
        val recreated = AloOfflineCacheStore(isolatedContext, prefs)
        assertEquals(42, owner(recreated.readBootstrapRaw(42)))
        assertEquals(41, owner(recreated.readBootstrapRaw(41)))
        recreated.clear(42)
        assertNull(recreated.readBootstrapRaw(42))
        assertEquals(41, owner(recreated.readBootstrapRaw(41)))
    }

    @Test fun aSnapshotFiledUnderTheWrongOwnerIsRejected() {
        cache.saveBootstrap(snapshot(41))
        val source = File(root, "alo-offline-cache/user-41/bootstrap-v2.bin")
        val wrongOwner = File(root, "alo-offline-cache/user-42/bootstrap-v2.bin")
        wrongOwner.parentFile!!.mkdirs()
        source.copyTo(wrongOwner)
        assertNull(cache.readBootstrapRaw(42))
        assertEquals(41, owner(cache.readBootstrapRaw(41)))
    }

    @Test fun legacyCacheMigratesOnlyForItsActualOwner() {
        val legacy = File(root, "alo-offline-cache/bootstrap.json")
        legacy.parentFile!!.mkdirs()
        legacy.writeText(snapshot(41).toString())
        assertNull(cache.readBootstrapRaw(42))
        assertTrue(legacy.exists())
        assertEquals(41, owner(cache.readBootstrapRaw(41)))
        assertFalse(legacy.exists())
        assertNull(cache.readBootstrapRaw(42))
    }

    @Test fun cachedMessageSearchUsesTheRequestedOwnerNotTheLastWriter() {
        cache.saveConversation(conversation("needle from b"), 42)
        cache.saveConversation(conversation("needle from a"), 41)
        assertEquals(listOf("needle from b"), cache.searchAllMessages(42, "needle").map { it.message.body })
        assertEquals(listOf("needle from b"), cache.searchMessages(42, 99, "needle").map { it.body })
        assertEquals(listOf("needle from a"), cache.searchAllMessages(41, "needle").map { it.message.body })
        assertTrue(cache.searchAllMessages(0, "needle").isEmpty())
    }

    private fun snapshot(owner: Int) = JSONObject().put("data", JSONObject().put("me", JSONObject().put("id", owner)))
    private fun owner(snapshot: JSONObject?) = snapshot?.optJSONObject("data")?.optJSONObject("me")?.optInt("id")
    private fun user(id: Int) = AloCurrentUser(
        id = id, name = "Fixture", username = "fixture$id", email = "fixture$id@example.test", bio = "",
        avatarUrl = "", avatarGallery = emptyList(), verified = false, registeredLabel = "", coverStyle = "", coverImageUrl = ""
    )
    private fun conversation(body: String) = AloConversationDetail(
        peerId = 99, kind = "direct", roomId = 0, title = "Fixture", subtitle = "", username = "fixture",
        lastSeenAt = "", avatarUrl = "", verified = false, online = false, memberCount = 0, entityType = "user",
        items = listOf(AloChatMessage(
            id = 1, roomId = 0, senderId = 99, recipientId = 42, body = body, attachmentUrl = "", attachmentType = "",
            attachmentName = "", createdAt = "2026-10-20T00:00:00Z", editedAt = "", readAt = ""
        ))
    )
}
