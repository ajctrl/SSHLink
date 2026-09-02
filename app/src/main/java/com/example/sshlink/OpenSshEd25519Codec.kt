package com.example.sshlink

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

object OpenSshEd25519Codec {
    private val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
    private const val TYPE = "ssh-ed25519"
    private const val DEFAULT_COMMENT = "sshlink-android"
    private const val LEGACY_COMMENT = "ssh-tunnel-android"

    data class GeneratedKey(
        val privateKeyPem: String,
        val publicKeyLine: String,
        val publicBlob: ByteArray,
    )

    fun generate(comment: String = DEFAULT_COMMENT, random: SecureRandom = SecureRandom()): GeneratedKey {
        val privateParams = Ed25519PrivateKeyParameters(random)
        val seed = privateParams.encoded
        val publicKey = privateParams.generatePublicKey().encoded
        return encode(seed, publicKey, comment, random.nextInt())
    }

    fun reconstructPublicLine(privateKeyPem: String, fallbackComment: String = DEFAULT_COMMENT): String {
        val parsed = parseAndValidate(privateKeyPem)
        val comment = parsed.comment.takeUnless { it.isBlank() || it == LEGACY_COMMENT } ?: fallbackComment
        return "$TYPE ${Base64.getEncoder().encodeToString(parsed.publicBlob)} $comment"
    }

    fun publicBlobFromPrivateKey(privateKeyPem: String): ByteArray = parseAndValidate(privateKeyPem).publicBlob

    private fun encode(seed: ByteArray, publicKey: ByteArray, comment: String, check: Int): GeneratedKey {
        require(seed.size == 32 && publicKey.size == 32)
        val publicBlob = blob { out ->
            sshString(out, TYPE.toByteArray(Charsets.US_ASCII))
            sshString(out, publicKey)
        }
        val privateBlock = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(check)
                out.writeInt(check)
                sshString(out, TYPE.toByteArray(Charsets.US_ASCII))
                sshString(out, publicKey)
                sshString(out, seed + publicKey)
                sshString(out, comment.toByteArray(Charsets.UTF_8))
                var padding = 1
                while (buffer.size() % 8 != 0) out.writeByte(padding++)
            }
        }.toByteArray()
        val binary = blob { out ->
            out.write(magic)
            sshString(out, "none".toByteArray(Charsets.US_ASCII))
            sshString(out, "none".toByteArray(Charsets.US_ASCII))
            sshString(out, byteArrayOf())
            out.writeInt(1)
            sshString(out, publicBlob)
            sshString(out, privateBlock)
        }
        val b64 = Base64.getEncoder().encodeToString(binary)
        val pem = buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            b64.chunked(70).forEach { appendLine(it) }
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
        val publicLine = "$TYPE ${Base64.getEncoder().encodeToString(publicBlob)} $comment"
        return GeneratedKey(pem, publicLine, publicBlob)
    }

    private data class Parsed(val publicBlob: ByteArray, val comment: String)

    private fun parseAndValidate(pem: String): Parsed {
        val body = pem.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        require(body.isNotEmpty()) { "OpenSSH private key body is empty" }
        val bytes = Base64.getDecoder().decode(body)
        val reader = Reader(bytes)
        require(reader.readRaw(magic.size).contentEquals(magic)) { "Not an OpenSSH private key" }
        require(reader.readStringAscii() == "none") { "Encrypted OpenSSH private keys are not supported" }
        require(reader.readStringAscii() == "none") { "Unexpected OpenSSH KDF" }
        require(reader.readString().isEmpty()) { "Unexpected OpenSSH KDF options" }
        require(reader.readInt() == 1) { "Expected exactly one OpenSSH key" }
        val outerBlob = reader.readString()
        val privateBlock = reader.readString()
        require(reader.remaining == 0) { "Trailing data after OpenSSH private key" }

        val outer = Reader(outerBlob)
        require(outer.readStringAscii() == TYPE) { "OpenSSH key is not Ed25519" }
        val outerPublic = outer.readString()
        require(outerPublic.size == 32 && outer.remaining == 0) { "Invalid Ed25519 public key" }

        val inner = Reader(privateBlock)
        val check1 = inner.readInt()
        val check2 = inner.readInt()
        require(check1 == check2) { "OpenSSH private-key checkints do not match" }
        require(inner.readStringAscii() == TYPE) { "Private OpenSSH key is not Ed25519" }
        val innerPublic = inner.readString()
        require(innerPublic.contentEquals(outerPublic)) { "OpenSSH public-key copies do not match" }
        val secret = inner.readString()
        require(secret.size == 64) { "Invalid Ed25519 private-key length" }
        val seed = secret.copyOfRange(0, 32)
        val secretPublic = secret.copyOfRange(32, 64)
        require(secretPublic.contentEquals(outerPublic)) { "Private block public key does not match" }
        val derived = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        require(derived.contentEquals(outerPublic)) { "Ed25519 public key does not match private seed" }
        val comment = inner.readString().toString(Charsets.UTF_8)
        var expectedPadding = 1
        while (inner.remaining > 0) {
            require(inner.readByte().toInt() and 0xff == expectedPadding) { "Invalid OpenSSH private-key padding" }
            expectedPadding++
        }
        return Parsed(outerBlob, comment)
    }

    private fun blob(block: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use(block) }.toByteArray()

    private fun sshString(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun readInt(): Int {
            require(remaining >= 4) { "Truncated OpenSSH key" }
            return ((readByte().toInt() and 0xff) shl 24) or
                ((readByte().toInt() and 0xff) shl 16) or
                ((readByte().toInt() and 0xff) shl 8) or
                (readByte().toInt() and 0xff)
        }

        fun readByte(): Byte {
            require(remaining > 0) { "Truncated OpenSSH key" }
            return bytes[offset++]
        }

        fun readRaw(length: Int): ByteArray {
            require(length >= 0 && remaining >= length) { "Truncated OpenSSH key" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readString(): ByteArray {
            val length = readInt()
            require(length >= 0) { "Negative OpenSSH string length" }
            return readRaw(length)
        }

        fun readStringAscii(): String = readString().toString(Charsets.US_ASCII)
    }
}
