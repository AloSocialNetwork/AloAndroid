package com.alo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AloCompositionReliabilityTest {
    private val cookieA = "alo_session=synthetic-account-a"
    private val cookieB = "alo_session=synthetic-account-b"
    private lateinit var app: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsName: String
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "composition-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        prefsName = "composition-test-${UUID.randomUUID()}"
        prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }
    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        root.deleteRecursively()
        app.deleteSharedPreferences(prefsName)
    }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scopes.add(it) }
    private fun drafts() = AloMessageDraftStore(context, AloOfflineCacheStore(context, prefs)) { throw AssertionError(it) }
    private fun conversation(peer: Int = 99) = AloConversationDetail(peerId = peer, kind = "direct", roomId = 0,
        title = "Synthetic", subtitle = "", username = "test", lastSeenAt = "", avatarUrl = "", verified = false,
        online = false, memberCount = 0, entityType = "user", items = emptyList())
    private fun reply() = AloChatMessage(id = 123, roomId = 0, senderId = 99, recipientId = 41, body = "Quoted text",
        attachmentUrl = "", attachmentType = "", attachmentName = "", createdAt = "", editedAt = "", readAt = "")
    private fun bootstrap(owner: Int) = AloBootstrapData(
        me = AloCurrentUser(owner, "Synthetic", "fixture$owner", "user@example.test", "", "", emptyList(), false, "", "", ""),
        feedPopular = emptyList(), feedSubscriptions = emptyList(), notifications = emptyList(), searchUsers = emptyList(), searchChannels = emptyList(),
        profileStats = AloProfileStats(0, 0, 0, 0, 0), profileRelations = AloProfileRelations(0, 0, 0, 0), profilePosts = emptyList(),
        notificationSettings = AloNotificationSettings(), privacySettings = AloPrivacySettings(), messagesConversations = emptyList(), messagesActivePeerId = 0, messagesItems = emptyList())

    @Test fun draftsSurviveRecreationAndKeepAccountsChatsTopicsReplyAndForwardSeparate() = runBlocking {
        val store = drafts()
        val chat = conversation()
        val original = PersistedMessageDraft("Account A", AloChatRecordMode.Voice, null,
            forwardDraft = listOf(AloMessageForwardSource(88, 3, "Author")), replyTo = reply(), selfDestructSeconds = 30)
        store.put(41, chat, original)
        store.put(42, chat, original.copy(text = "Account B"))
        store.put(41, conversation(100), original.copy(text = "Other chat"))
        store.put(41, chat, original.copy(text = "Topic"), topicId = 7)
        store.flush()
        val restored = drafts()
        assertEquals(original, restored.restoredFor(41, chat))
        assertEquals("Account B", restored.restoredFor(42, chat)?.text)
        assertEquals("Other chat", restored.restoredFor(41, conversation(100))?.text)
        assertEquals("Topic", restored.restoredFor(41, chat, 7)?.text)
        assertNull(restored.restoredFor(43, chat))
        val file = File(root, "alo-secure-outbox/user-41/drafts-v1.bin")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Account A"))
    }

    @Test fun clearingOneDraftDoesNotClearAnotherAccountOrChat() = runBlocking {
        val store = drafts()
        val state = AloNativeAppUiState(bootstrap = bootstrap(41), activeConversation = conversation(), activeConversationPeerId = 99,
            messageComposerText = "A", messageReplyTo = reply())
        store.persistFrom(state)
        store.persistFrom(state.copy(bootstrap = bootstrap(42), messageComposerText = "B"))
        store.clearActive(state)
        store.flush()
        val restored = drafts()
        assertNull(restored.restoredFor(41, conversation()))
        assertEquals("B", restored.restoredFor(42, conversation())?.text)
    }

    @Test fun changingAccountClearsCarriedComposerBeforeItsOwnDraftIsRestored() {
        val previous = AloNativeAppUiState(bootstrap = bootstrap(41), activeConversation = conversation(),
            messageComposerText = "Account A", messageReplyTo = reply(), messageSelfDestructSeconds = 30,
            messageForwardDraft = listOf(AloMessageForwardSource(99, 1, "Author")))
        val next = isolateMessageDraftOwner(previous, previous.copy(bootstrap = bootstrap(42)))
        assertEquals("", next.messageComposerText)
        assertNull(next.messageReplyTo)
        assertTrue(next.messageForwardDraft.isEmpty())
        assertEquals(0, next.messageSelfDestructSeconds)
        assertEquals("Account A", isolateMessageDraftOwner(previous, previous).messageComposerText)
    }

    @Test fun pendingPushPersistsAcrossColdStartAndNeverUsesUntrustedIds() {
        assertTrue(AloPendingPushOpenStore.isValidChatTarget(99, 0))
        assertTrue(AloPendingPushOpenStore.isValidChatTarget(1_000_000_007, 7))
        assertFalse(AloPendingPushOpenStore.isValidChatTarget(-7, 7))
        assertFalse(AloPendingPushOpenStore.isValidChatTarget(1_000_000_008, 7))
        val token = "a".repeat(64)
        val store = AloPendingPushOpenStore(prefs)
        store.capture("../../post/123", "2")
        store.capture(token, "1")
        assertNull(store.next(41))
        store.capture(token, "2")
        val recreated = AloPendingPushOpenStore(prefs)
        assertEquals(token, recreated.next(41))
        recreated.unavailableFor(token, 41)
        assertNull(recreated.next(41))
        assertEquals(token, recreated.next(42))
        recreated.complete(token)
        assertNull(AloPendingPushOpenStore(prefs).next(42))
    }

    @Test fun queuedMediaIsCopiedAndEncryptedBeforeEditorCanClose() = runBlocking {
        val api = AloApiClient("http://127.0.0.1:1/", context)
        val queue = AloPublicationQueue(context, api, scope(), { cookieA }, { _, _ -> }, { _, _, _ -> }, { _, _ -> })
        val bytes = "SYNTHETIC_PRIVATE_MEDIA".toByteArray()
        val id = queue.enqueue(41, "story", "Caption", listOf(AloStoryUploadPage(bytes, null, "image/jpeg", "image")))
        bytes.fill(0)
        val records = AloSecureOutboxStore(context).loadDocument("publications", 41)
        assertEquals(id, records.getJSONObject(0).getString("id"))
        assertEquals(0, AloSecureOutboxStore(context).loadDocument("publications", 42).length())
        val file = File(root, "alo-publications/user-41/$id/0.bin")
        assertTrue(file.isFile)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("SYNTHETIC_PRIVATE_MEDIA"))
    }

    @Test fun publicationStorageLimitRejectsWithoutSendingOrLeavingOrphanFiles() = runBlocking {
        val queue = AloPublicationQueue(context, AloApiClient("http://127.0.0.1:1/", context), scope(), { cookieA },
            { _, _ -> }, { _, _, _ -> }, { _, _ -> }, maxPendingBytes = 64)
        val error = runCatching { queue.enqueue(41, "post", "Caption", listOf(AloStoryUploadPage(ByteArray(100), null, "image/jpeg", "image"))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, AloSecureOutboxStore(context).loadDocument("publications", 41).length())
        assertTrue(File(root, "alo-publications/user-41").listFiles().orEmpty().isEmpty())
    }

    @Test fun secureDocumentRecoversTheAtomicBackupAfterInterruptedWrite() {
        val storage = AloSecureOutboxStore(context)
        storage.saveDocument("drafts", org.json.JSONArray().put(JSONObject().put("owner", 41)), 41)
        val file = File(root, "alo-secure-outbox/user-41/drafts-v1.bin")
        file.copyTo(File(file.path + ".bak"))
        file.delete()
        assertEquals(41, AloSecureOutboxStore(context).loadDocument("drafts", 41).getJSONObject(0).getInt("owner"))
    }

    /** Run with -e phase write, force-stop the target, then -e phase read. */
    @Test fun realProcessDeathFixture(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        org.junit.Assume.assumeTrue(phase == "write" || phase == "read")
        val persistent = File(app.filesDir, "composition-process-death-qa")
        val fixture = object : ContextWrapper(app) {
            override fun getFilesDir(): File = persistent
            override fun getCacheDir(): File = File(persistent, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        val store = AloMessageDraftStore(fixture, AloOfflineCacheStore(fixture, prefs)) { throw AssertionError(it) }
        if (phase == "write") {
            persistent.mkdirs()
            store.persistFrom(AloNativeAppUiState(bootstrap = bootstrap(41), activeConversation = conversation(), activeConversationPeerId = 99,
                isLoadingConversation = true, messageComposerText = "Survives process death", messageRecorderMode = AloChatRecordMode.VideoCircle,
                messageReplyTo = reply(), messageSelfDestructSeconds = 30, messageForwardDraft = listOf(AloMessageForwardSource(99, 1, "Synthetic"))))
            store.flush()
            val queue = AloPublicationQueue(fixture, AloApiClient("http://127.0.0.1:1/", fixture), scope(), { cookieA },
                { _, _ -> }, { _, _, _ -> }, { _, _ -> })
            queue.enqueue(41, "story", "Survives process death", listOf(AloStoryUploadPage("Synthetic file".toByteArray(), null, "image/jpeg", "image")))
        } else {
            try {
                val restored = requireNotNull(store.restoredFor(41, conversation()))
                assertEquals("Survives process death", restored.text)
                assertEquals(123, restored.replyTo?.id)
                assertEquals(30, restored.selfDestructSeconds)
                assertEquals(1, restored.forwardDraft.size)
                assertNull(store.restoredFor(42, conversation()))
                val jobs = AloSecureOutboxStore(fixture).loadDocument("publications", 41)
                assertEquals(1, jobs.length())
                val job = jobs.getJSONObject(0)
                val metadata = requireNotNull(AloChunkedMediaCrypto.parseMetadata(job.getJSONArray("files").getJSONObject(0).getJSONObject("encryption")))
                val output = java.io.ByteArrayOutputStream()
                AloChunkedMediaCrypto.decryptTo(File(persistent, "alo-publications/user-41/${job.getString("id")}/0.bin"), metadata, output)
                assertEquals("Synthetic file", output.toString("UTF-8"))
            } finally { persistent.deleteRecursively() }
        }
        Unit
    }

    @Test fun postLostAckAndProcessRestartReplaysTheSameOperation() = lostAck("post")
    @Test fun storyLostAckAndProcessRestartReplaysTheSameOperation() = lostAck("story")
    @Test fun mediaPostRetryReusesUploadedMediaAndTheOriginalOperation() = lostAck("post", true)
    @Test fun mediaStoryRetryReusesUploadedMediaAndTheOriginalOperation() = lostAck("story", true)

    @Test fun accountSwitchDuringPublicationNeverDeliversTheOldObjectToTheNewAccount() = runBlocking {
        ReplayServer().use { server ->
            val callbacks = CopyOnWriteArrayList<Int>()
            var cookie = cookieA
            val queue = AloPublicationQueue(context, AloApiClient(server.url, context), scope(), { cookie }, { _, _ -> },
                { owner, _, _ -> callbacks.add(owner) }, { owner, _ -> callbacks.add(owner) })
            queue.enqueue(41, "post", "Saved intent", emptyList())
            queue.activate(41, cookieA)
            assertTrue(server.received.await(10, TimeUnit.SECONDS))
            cookie = cookieB
            queue.activate(42, cookieB)
            server.release.countDown()
            delay(300)
            assertTrue(callbacks.isEmpty())
            assertEquals(0, AloSecureOutboxStore(context).loadDocument("publications", 42).length())
            cookie = cookieA
            queue.activate(41, cookieA)
            repeat(150) { if (callbacks.isEmpty()) delay(100) }
            assertEquals(listOf(41), callbacks.toList())
        }
    }

    private fun lostAck(kind: String, withMedia: Boolean = false) = runBlocking {
        ReplayServer().use { server ->
            val api = AloApiClient(server.url, context)
            val firstScope = scope()
            val callbacks = CopyOnWriteArrayList<Int>()
            fun queue(scope: CoroutineScope) = AloPublicationQueue(context, api, scope, { cookieA }, { _, _ -> },
                { owner, _, _ -> callbacks.add(owner) }, { owner, _ -> callbacks.add(owner) })
            val first = queue(firstScope)
            val pages = if (withMedia) listOf(AloStoryUploadPage("Synthetic image".toByteArray(), null, "image/jpeg", "image")) else emptyList()
            val id = first.enqueue(41, kind, "Saved intent", pages)
            first.activate(41, cookieA)
            assertTrue(server.received.await(10, TimeUnit.SECONDS))
            firstScope.cancel() // Simulates the old process going away before ACK.
            server.release.countDown()
            delay(150)
            val restored = queue(scope())
            restored.activate(41, cookieA)
            repeat(150) { if (callbacks.isEmpty()) delay(100) }
            assertEquals(listOf(41), callbacks.toList())
            assertEquals(setOf(id), server.operations.toSet())
            assertTrue(server.operations.size >= 2)
            assertEquals(if (withMedia) 1 else 0, server.uploadCreations.get())
            assertEquals(0, AloSecureOutboxStore(context).loadDocument("publications", 41).length())
        }
    }

    /** Loopback only, no real users, credentials or network services. */
    private class ReplayServer : AutoCloseable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:${socket.localPort}/"
        val operations = CopyOnWriteArrayList<String>()
        val uploadCreations = AtomicInteger()
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val count = AtomicInteger()
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread {
                    client.use {
                        runCatching {
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine()
                            var length = 0
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                            }
                            val chars = CharArray(length)
                            var offset = 0
                            while (offset < length) offset += reader.read(chars, offset, length - offset).also { require(it > 0) }
                            val response = if (request.contains("/api/uploads/resumable")) {
                                if (request.startsWith("POST ")) {
                                    require(JSONObject(String(chars)).getInt("expectedOwnerId") == 41)
                                    uploadCreations.incrementAndGet()
                                    JSONObject().put("ok", true).put("upload", JSONObject().put("id", "1".repeat(32)).put("offset", 0).put("chunkSize", 65536))
                                } else {
                                    JSONObject().put("ok", true).put("complete", true)
                                        .put("file", JSONObject().put("url", "/uploads/synthetic.jpg").put("mime", "image/jpeg"))
                                }
                            } else {
                                val payload = JSONObject(String(chars))
                                require(payload.getInt("expectedOwnerId") == 41)
                                if (uploadCreations.get() > 0) require(payload.getJSONArray("media").getJSONObject(0).getString("url") == "/uploads/synthetic.jpg")
                                operations.add(payload.getString("clientOperationId"))
                                if (count.incrementAndGet() == 1) { received.countDown(); release.await(10, TimeUnit.SECONDS) }
                                val kind = if (request.contains("/api/posts")) "post" else "story"
                                JSONObject().put("ok", true).put(kind, JSONObject().put("id", 321).put("userId", 41)
                                    .put("body", "Saved intent").put("author", JSONObject().put("id", 41).put("name", "Synthetic")))
                            }
                            val body = response.toString().toByteArray()
                            client.getOutputStream().apply {
                                write("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(body); flush()
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { release.countDown(); socket.close(); thread.join(1_000) }
    }
}
