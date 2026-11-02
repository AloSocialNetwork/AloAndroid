package com.alo.android

import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class AloCallSecurityV2KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
)

internal enum class AloCallSecurityV2Role {
    Caller,
    Callee
}

internal class AloCallSecurityV2Handshake(
    val role: AloCallSecurityV2Role,
    val callId: Int,
    val callerId: Int,
    val calleeId: Int,
    val localKeyPair: AloCallSecurityV2KeyPair = AloCallSecurityV2.generateKeyPair(),
    val localNonce: ByteArray = AloCallSecurityV2.randomNonce()
) {
    var callerCommitment: ByteArray? = null
    var remotePublicKey: ByteArray? = null
    var remoteNonce: ByteArray? = null
    var sharedKey: ByteArray? = null
    var offerFingerprint: String = ""
    var answerFingerprint: String = ""
    var transcriptHash: ByteArray? = null
    var didAccept: Boolean = false
    var didSendCommit: Boolean = false
    var didSendResponse: Boolean = false
    var didStartOffer: Boolean = false
    var didSendConfirm: Boolean = false
    var isConfirmed: Boolean = false
    var reconnectGeneration: Int = 0
    var lastHandledReconnectGeneration: Int = 0
    var reconnectOfferHash: ByteArray? = null

    init {
        require(callId > 0 && callerId > 0 && calleeId > 0 && callerId != calleeId)
        require(localKeyPair.privateKey.size == 32 && localKeyPair.publicKey.size == 32)
        require(localNonce.size == 32)
        if (role == AloCallSecurityV2Role.Caller) {
            callerCommitment = AloCallSecurityV2.commitment(
                callId = callId,
                callerId = callerId,
                calleeId = calleeId,
                callerPublicKey = localKeyPair.publicKey,
                callerNonce = localNonce
            )
        }
    }

    val callerPublicKey: ByteArray?
        get() = if (role == AloCallSecurityV2Role.Caller) localKeyPair.publicKey else remotePublicKey

    val callerNonce: ByteArray?
        get() = if (role == AloCallSecurityV2Role.Caller) localNonce else remoteNonce

    val calleePublicKey: ByteArray?
        get() = if (role == AloCallSecurityV2Role.Callee) localKeyPair.publicKey else remotePublicKey

    val calleeNonce: ByteArray?
        get() = if (role == AloCallSecurityV2Role.Callee) localNonce else remoteNonce

    fun deriveSharedKeyIfPossible(): ByteArray {
        sharedKey?.let { return it }
        val remote = requireNotNull(remotePublicKey)
        return AloCallSecurityV2.sharedKey(
            privateKey = localKeyPair.privateKey,
            remotePublicKey = remote,
            callId = callId,
            callerId = callerId,
            calleeId = calleeId
        ).also { sharedKey = it }
    }
}

internal object AloCallSecurityV2 {
    const val VERSION: Int = 2

    private val secureRandom = SecureRandom()

    fun generateKeyPair(): AloCallSecurityV2KeyPair {
        val keyPair = ECKeyPair.generate()
        return AloCallSecurityV2KeyPair(
            privateKey = keyPair.privateKey.serialize(),
            publicKey = keyPair.publicKey.publicKeyBytes
        )
    }

    fun randomNonce(): ByteArray = ByteArray(32).also(secureRandom::nextBytes)

    fun sharedKey(
        privateKey: ByteArray,
        remotePublicKey: ByteArray,
        callId: Int,
        callerId: Int,
        calleeId: Int
    ): ByteArray {
        require32(privateKey)
        require32(remotePublicKey)
        val local = ECPrivateKey(privateKey)
        val remote = ECPublicKey.fromPublicKeyBytes(remotePublicKey)
        val agreement = local.calculateAgreement(remote)
        val salt = sha256(
            canonicalData(
                label = "kdf",
                fields = listOf(
                    "call-id" to callId.toString(),
                    "caller-id" to callerId.toString(),
                    "callee-id" to calleeId.toString()
                )
            )
        )
        return hkdfSha256(
            inputKeyMaterial = agreement,
            salt = salt,
            info = "alo-call-security-v2/shared-key".toByteArray(Charsets.UTF_8),
            outputByteCount = 32
        )
    }

    fun commitment(
        callId: Int,
        callerId: Int,
        calleeId: Int,
        callerPublicKey: ByteArray,
        callerNonce: ByteArray
    ): ByteArray {
        require32(callerPublicKey)
        require32(callerNonce)
        return sha256(
            canonicalData(
                label = "commit",
                fields = listOf(
                    "call-id" to callId.toString(),
                    "caller-id" to callerId.toString(),
                    "callee-id" to calleeId.toString(),
                    "caller-public" to base64Url(callerPublicKey),
                    "caller-nonce" to base64Url(callerNonce)
                )
            )
        )
    }

    fun offerTranscriptHash(
        callId: Int,
        callerId: Int,
        calleeId: Int,
        callerCommitment: ByteArray,
        callerPublicKey: ByteArray,
        callerNonce: ByteArray,
        calleePublicKey: ByteArray,
        calleeNonce: ByteArray,
        offerFingerprint: String
    ): ByteArray {
        requireHandshakeValues(
            callerCommitment,
            callerPublicKey,
            callerNonce,
            calleePublicKey,
            calleeNonce
        )
        require(isNormalizedFingerprint(offerFingerprint))
        return sha256(
            canonicalData(
                label = "offer-transcript",
                fields = listOf(
                    "call-id" to callId.toString(),
                    "caller-id" to callerId.toString(),
                    "callee-id" to calleeId.toString(),
                    "caller-commitment" to base64Url(callerCommitment),
                    "caller-public" to base64Url(callerPublicKey),
                    "caller-nonce" to base64Url(callerNonce),
                    "callee-public" to base64Url(calleePublicKey),
                    "callee-nonce" to base64Url(calleeNonce),
                    "offer-dtls" to offerFingerprint
                )
            )
        )
    }

    fun transcriptHash(
        callId: Int,
        callerId: Int,
        calleeId: Int,
        callerCommitment: ByteArray,
        callerPublicKey: ByteArray,
        callerNonce: ByteArray,
        calleePublicKey: ByteArray,
        calleeNonce: ByteArray,
        offerFingerprint: String,
        answerFingerprint: String
    ): ByteArray {
        requireHandshakeValues(
            callerCommitment,
            callerPublicKey,
            callerNonce,
            calleePublicKey,
            calleeNonce
        )
        require(isNormalizedFingerprint(offerFingerprint))
        require(isNormalizedFingerprint(answerFingerprint))
        return sha256(
            canonicalData(
                label = "transcript",
                fields = listOf(
                    "call-id" to callId.toString(),
                    "caller-id" to callerId.toString(),
                    "callee-id" to calleeId.toString(),
                    "caller-commitment" to base64Url(callerCommitment),
                    "caller-public" to base64Url(callerPublicKey),
                    "caller-nonce" to base64Url(callerNonce),
                    "callee-public" to base64Url(calleePublicKey),
                    "callee-nonce" to base64Url(calleeNonce),
                    "offer-dtls" to offerFingerprint,
                    "answer-dtls" to answerFingerprint
                )
            )
        )
    }

    fun proof(
        sharedKey: ByteArray,
        role: String,
        transcriptHash: ByteArray
    ): ByteArray {
        require32(sharedKey)
        require32(transcriptHash)
        require(
            role in setOf(
                "caller-offer",
                "callee-answer",
                "caller-confirm",
                "caller-reconnect-offer",
                "callee-reconnect-answer"
            )
        )
        return hmacSha256(
            key = sharedKey,
            value = "alo-call-security-v2/proof/$role\n".toByteArray(Charsets.UTF_8) +
                transcriptHash
        )
    }

    fun reconnectTranscriptHash(
        callId: Int,
        generation: Int,
        initialTranscriptHash: ByteArray,
        offerFingerprint: String,
        offerSdpHash: ByteArray,
        answerFingerprint: String? = null,
        answerSdpHash: ByteArray? = null
    ): ByteArray {
        require(callId > 0 && generation > 0)
        require32(initialTranscriptHash)
        require32(offerSdpHash)
        require(isNormalizedFingerprint(offerFingerprint))
        val fields = mutableListOf(
            "call-id" to callId.toString(),
            "generation" to generation.toString(),
            "initial-transcript" to base64Url(initialTranscriptHash),
            "offer-dtls" to offerFingerprint,
            "offer-sdp" to base64Url(offerSdpHash)
        )
        if (answerFingerprint != null && answerSdpHash != null) {
            require32(answerSdpHash)
            require(isNormalizedFingerprint(answerFingerprint))
            fields += "answer-dtls" to answerFingerprint
            fields += "answer-sdp" to base64Url(answerSdpHash)
        } else {
            require(answerFingerprint == null && answerSdpHash == null)
        }
        return sha256(
            canonicalData(
                label = if (answerFingerprint == null) {
                    "reconnect-offer"
                } else {
                    "reconnect-answer"
                },
                fields = fields
            )
        )
    }

    fun sdpHash(sdp: String): ByteArray = sha256(sdp.toByteArray(Charsets.UTF_8))

    fun emojis(
        sharedKey: ByteArray,
        transcriptHash: ByteArray,
        callerPublicKey: ByteArray
    ): List<String> {
        require32(sharedKey)
        require32(transcriptHash)
        require32(callerPublicKey)
        val digest = hmacSha256(
            key = sharedKey,
            value = "alo-call-security-v2/emoji\n".toByteArray(Charsets.UTF_8) +
                transcriptHash +
                callerPublicKey
        )
        return (0 until 4).map { part ->
            var value = 0L
            val offset = part * 8
            for (index in offset until offset + 8) {
                value = (value shl 8) or (digest[index].toLong() and 0xffL)
            }
            value = value and Long.MAX_VALUE
            EMOJI_TABLE[(value % EMOJI_TABLE.size.toLong()).toInt()]
        }
    }

    fun dtlsFingerprint(sdp: String): String? {
        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            if (!line.startsWith("a=fingerprint:", ignoreCase = true)) continue
            val value = line.substring("a=fingerprint:".length)
            val components = value.split(Regex("\\s+"), limit = 2)
            if (components.size != 2 || !components[0].equals("sha-256", ignoreCase = true)) {
                continue
            }
            val hex = components[1].replace(":", "").uppercase()
            if (hex.length == 64 && hex.all { it in "0123456789ABCDEF" }) {
                return "sha-256:$hex"
            }
        }
        return null
    }

    fun base64Url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun decode32(value: String): ByteArray {
        val data = Base64.getUrlDecoder().decode(value.trim())
        require32(data)
        return data
    }

    fun timingSafeEqual(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)

    private fun canonicalData(
        label: String,
        fields: List<Pair<String, String>>
    ): ByteArray = buildString {
        append("alo-call-security-v2/")
        append(label)
        append('\n')
        fields.forEach { (name, value) ->
            append(name)
            append('=')
            append(value)
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputByteCount: Int
    ): ByteArray {
        require(outputByteCount in 1..(255 * 32))
        val pseudoRandomKey = hmacSha256(salt, inputKeyMaterial)
        val output = ByteArray(outputByteCount)
        var generated = 0
        var block = ByteArray(0)
        var counter = 1
        while (generated < outputByteCount) {
            block = hmacSha256(
                pseudoRandomKey,
                block + info + byteArrayOf(counter.toByte())
            )
            val count = minOf(block.size, outputByteCount - generated)
            block.copyInto(output, destinationOffset = generated, endIndex = count)
            generated += count
            counter += 1
        }
        return output
    }

    private fun requireHandshakeValues(vararg values: ByteArray) {
        values.forEach(::require32)
    }

    private fun require32(value: ByteArray) {
        require(value.size == 32)
    }

    private fun isNormalizedFingerprint(value: String): Boolean {
        if (!value.startsWith("sha-256:") || value.length != 72) return false
        return value.substring("sha-256:".length).all { it in "0123456789ABCDEF" }
    }

    private val EMOJI_TABLE = listOf(
        "😉", "😍", "😛", "😭", "😱", "😡", "😎", "😴", "😵", "😈", "😬", "😇", "😏", "👮", "👷", "💂",
        "👶", "👨", "👩", "👴", "👵", "😻", "😽", "🙀", "👺", "🙈", "🙉", "🙊", "💀", "👽", "💩", "🔥",
        "💥", "💤", "👂", "👀", "👃", "👅", "👄", "👍", "👎", "👌", "👊", "✌", "✋", "👐", "👆", "👇",
        "👉", "👈", "🙏", "👏", "💪", "🚶", "🏃", "💃", "👫", "👨‍👩‍👦", "👬", "👭", "💅", "🎩", "👑", "👒", "👟",
        "👞", "👠", "👕", "👗", "👖", "👙", "👜", "👓", "🎀", "💄", "💛", "💙", "💜", "💚", "💍", "💎",
        "🐶", "🐺", "🐱", "🐭", "🐹", "🐰", "🐸", "🐯", "🐨", "🐻", "🐷", "🐮", "🐗", "🐴", "🐑", "🐘",
        "🐼", "🐧", "🐥", "🐔", "🐍", "🐢", "🐛", "🐝", "🐜", "🐞", "🐌", "🐙", "🐚", "🐟", "🐬", "🐋",
        "🐐", "🐊", "🐫", "🍀", "🌹", "🌻", "🍁", "🌾", "🍄", "🌵", "🌴", "🌳", "🌞", "🌚", "🌙", "🌎",
        "🌋", "⚡", "☔", "❄", "⛄", "🌀", "🌈", "🌊", "🎓", "🎆", "🎃", "👻", "🎅", "🎄", "🎁", "🎈",
        "🔮", "🎥", "📷", "💿", "💻", "☎", "📡", "📺", "📻", "🔉", "🔔", "⏳", "⏰", "⌚", "🔒", "🔑",
        "🔎", "💡", "🔦", "🔌", "🔋", "🚿", "🚽", "🔧", "🔨", "🚪", "🚬", "💣", "🔫", "🔪", "💊", "💉",
        "💰", "💵", "💳", "✉", "📫", "📦", "📅", "📁", "✂", "📌", "📎", "✒", "✏", "📐", "📚", "🔬",
        "🔭", "🎨", "🎬", "🎤", "🎧", "🎵", "🎹", "🎻", "🎺", "🎸", "👾", "🎮", "🃏", "🎲", "🎯", "🏈",
        "🏀", "⚽", "⚾", "🎾", "🎱", "🏉", "🎳", "🏁", "🏇", "🏆", "🏊", "🏄", "☕", "🍼", "🍺", "🍷",
        "🍴", "🍕", "🍔", "🍟", "🍗", "🍱", "🍚", "🍜", "🍡", "🍳", "🍞", "🍩", "🍦", "🎂", "🍰", "🍪",
        "🍫", "🍭", "🍯", "🍎", "🍏", "🍊", "🍋", "🍒", "🍇", "🍉", "🍓", "🍑", "🍌", "🍐", "🍍", "🍆",
        "🍅", "🌽", "🏡", "🏥", "🏦", "⛪", "🏰", "⛺", "🏭", "🗻", "🗽", "🎠", "🎡", "⛲", "🎢", "🚢",
        "🚤", "⚓", "🚀", "✈", "🚁", "🚂", "🚋", "🚎", "🚌", "🚙", "🚗", "🚕", "🚛", "🚨", "🚔", "🚒",
        "🚑", "🚲", "🚠", "🚜", "🚦", "⚠", "🚧", "⛽", "🎰", "🗿", "🎪", "🎭", "🇯🇵", "🇰🇷", "🇩🇪", "🇨🇳",
        "🇺🇸", "🇫🇷", "🇪🇸", "🇮🇹", "🇷🇺", "🇬🇧", "1⃣", "2⃣", "3⃣", "4⃣", "5⃣", "6⃣", "7⃣", "8⃣", "9⃣", "0⃣",
        "🔟", "❗", "❓", "♥", "♦", "💯", "🔗", "🔱", "🔴", "🔵", "🔶", "🔷"
    )
}
