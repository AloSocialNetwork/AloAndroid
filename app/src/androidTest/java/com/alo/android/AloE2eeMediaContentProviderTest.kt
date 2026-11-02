package com.alo.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AloE2eeMediaContentProviderTest {
    @Test
    fun servesChunkedDurableDraftAcrossChunkBoundary() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.filesDir, "alo-e2ee-media/drafts/provider-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val plaintext = ByteArray(AloChunkedMediaCrypto.DEFAULT_CHUNK_SIZE + 73) { index ->
            (index % 251).toByte()
        }
        val source = File(root, "source.bin").apply { writeBytes(plaintext) }
        val sealed = AloChunkedMediaCrypto.encrypt(
            source = source,
            destination = File(root, "attachment.bin"),
            mime = "video/mp4"
        )
        source.delete()
        val uri = AloE2eeMediaContentProvider.register(
            context = context,
            encryptedFile = sealed.encryptedFile,
            encryption = JSONObject(sealed.metadata.asMap()),
            type = "video",
            name = "attachment.mp4"
        )

        val opened = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Provider did not return a file descriptor")
        opened.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val start = AloChunkedMediaCrypto.DEFAULT_CHUNK_SIZE - 17
                input.channel.position(start.toLong())
                val fragment = ByteArray(64)
                val count = input.read(fragment)
                assertEquals(64, count)
                assertArrayEquals(
                    plaintext.copyOfRange(start, start + count),
                    fragment.copyOf(count)
                )
            }
        }

        assertFalse(File(context.cacheDir, "alo-e2ee-media/decoded").exists())
    }

    @Test
    fun decryptsWithoutPersistingPlaintextAndSupportsSeeking() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "alo-e2ee-media")
        root.deleteRecursively()
        val crypto = AloE2eeCrypto(context.getSharedPreferences("provider-test", 0))
        val plaintext = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        val box = crypto.encryptMedia(plaintext)
        val encryptedFile = File(root, "encrypted/provider-test.bin").apply {
            parentFile?.mkdirs()
            writeBytes(box.data)
        }
        val encryption = JSONObject()
            .put("algorithm", AloE2eeCrypto.MEDIA_SCHEME)
            .put("key", box.key)
            .put("iv", box.iv)
            .put("mime", "video/mp4")
        val uri = AloE2eeMediaContentProvider.register(
            context = context,
            encryptedFile = encryptedFile,
            encryption = encryption,
            type = "video",
            name = "provider-test.mp4"
        )

        val opened = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Provider did not return a file descriptor")
        opened.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                assertArrayEquals(plaintext, input.readBytes())
            }
        }

        val seeked = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Provider did not return a seekable descriptor")
        seeked.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                input.channel.position(64_017)
                val fragment = ByteArray(4_096)
                val count = input.read(fragment)
                assertArrayEquals(
                    plaintext.copyOfRange(64_017, 64_017 + count),
                    fragment.copyOf(count)
                )
            }
        }

        assertFalse(File(root, "decoded").exists())
        val registry = context.getSharedPreferences("alo-e2ee-media-registry-v1", 0)
        val stored = registry.all.values.joinToString()
        assertFalse(stored.contains(box.key))
        assertFalse(stored.contains(box.iv))
    }
}
