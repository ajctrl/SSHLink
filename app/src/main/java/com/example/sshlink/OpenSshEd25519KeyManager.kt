package com.example.sshlink

import android.content.Context
import java.io.File

class OpenSshEd25519KeyManager(context: Context) {
    private val keyDir = File(context.applicationContext.filesDir, "keys")
    val privateKeyFile = File(keyDir, "id_ed25519")
    val publicKeyFile = File(keyDir, "id_ed25519.pub")

    fun hasPrivateKey(): Boolean = synchronized(KEY_LOCK) {
        privateKeyFile.isFile && privateKeyFile.length() > 0
    }

    /**
     * The private key is the source of truth. A missing or stale .pub is repaired
     * only after the OpenSSH private key is fully parsed and the Ed25519 public key
     * is re-derived from its seed.
     */
    fun publicKeyText(): String? = synchronized(KEY_LOCK) {
        if (!hasPrivateKey()) return@synchronized null
        val privateText = privateKeyFile.readText(Charsets.US_ASCII)
        val reconstructed = OpenSshEd25519Codec.reconstructPublicLine(privateText)
        val existing = publicKeyFile.takeIf { it.isFile }
            ?.readText(Charsets.US_ASCII)
            ?.trim()
        if (existing != reconstructed) {
            AtomicFileWriter.writeText(publicKeyFile, "$reconstructed\n", Charsets.US_ASCII)
            harden(publicKeyFile)
        }
        reconstructed
    }

    fun generate(): String = synchronized(KEY_LOCK) {
        keyDir.mkdirs()
        val generated = OpenSshEd25519Codec.generate()
        // Private key first: if the process dies before .pub replacement,
        // publicKeyText() repairs the public file from the private key.
        AtomicFileWriter.writeText(privateKeyFile, generated.privateKeyPem, Charsets.US_ASCII)
        harden(privateKeyFile)
        AtomicFileWriter.writeText(publicKeyFile, generated.publicKeyLine + "\n", Charsets.US_ASCII)
        harden(publicKeyFile)
        generated.publicKeyLine
    }

    fun validatePrivateKey(): Boolean = synchronized(KEY_LOCK) {
        if (!hasPrivateKey()) return@synchronized false
        runCatching {
            OpenSshEd25519Codec.publicBlobFromPrivateKey(privateKeyFile.readText(Charsets.US_ASCII))
        }.isSuccess
    }

    private fun harden(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private companion object {
        val KEY_LOCK = Any()
    }
}
