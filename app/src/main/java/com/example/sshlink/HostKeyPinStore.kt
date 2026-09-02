package com.example.sshlink

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class HostKeyPinStore(private val file: File) {
    enum class CheckResult { MISSING, PINNED, MATCH, CHANGED }

    /** Inspect without mutating persistent state. */
    fun inspect(alias: String, key: ByteArray): CheckResult = synchronized(FILE_LOCK) {
        val canonical = SshHostAlias.canonicalAlias(alias)
        val existing = loadNormalized()[canonical]
        when {
            existing == null -> CheckResult.MISSING
            MessageDigest.isEqual(existing, key) -> CheckResult.MATCH
            else -> CheckResult.CHANGED
        }
    }

    /** Commit a key only if no conflicting pin has appeared in the meantime. */
    fun commit(alias: String, key: ByteArray): CheckResult = synchronized(FILE_LOCK) {
        val canonical = SshHostAlias.canonicalAlias(alias)
        val pins = loadNormalized()
        val existing = pins[canonical]
        when {
            existing == null -> {
                pins[canonical] = key.copyOf()
                save(pins)
                CheckResult.PINNED
            }
            MessageDigest.isEqual(existing, key) -> CheckResult.MATCH
            else -> CheckResult.CHANGED
        }
    }

    /** Kept for direct store users/tests; equivalent to an immediate TOFU commit. */
    fun check(alias: String, key: ByteArray): CheckResult = commit(alias, key)

    fun put(alias: String, key: ByteArray) = synchronized(FILE_LOCK) {
        val pins = loadNormalized()
        pins[SshHostAlias.canonicalAlias(alias)] = key.copyOf()
        save(pins)
    }

    fun forget(alias: String): Boolean = synchronized(FILE_LOCK) {
        val pins = loadNormalized()
        val removed = pins.remove(SshHostAlias.canonicalAlias(alias)) != null
        if (removed) save(pins)
        removed
    }

    fun remove(alias: String, predicate: (ByteArray) -> Boolean): Boolean = synchronized(FILE_LOCK) {
        val pins = loadNormalized()
        val canonical = SshHostAlias.canonicalAlias(alias)
        val value = pins[canonical] ?: return@synchronized false
        if (!predicate(value)) return@synchronized false
        pins.remove(canonical)
        save(pins)
        true
    }

    fun entries(): LinkedHashMap<String, ByteArray> = synchronized(FILE_LOCK) {
        loadNormalized().mapValuesTo(linkedMapOf()) { (_, value) -> value.copyOf() }
    }

    /**
     * Reset the entire pin database without destroying the previous bytes.
     * The existing file is moved to an app-private .corrupt backup first so a
     * malformed database can be recovered without deleting the SSH private key.
     */
    fun resetAllPreservingBackup(): File? = synchronized(FILE_LOCK) {
        if (!file.exists()) return@synchronized null
        val parent = file.parentFile ?: throw PinStoreException("SSH host-key pin store has no parent directory")
        val backup = generateSequence(0) { it + 1 }
            .map { suffix -> File(parent, file.name + ".corrupt" + if (suffix == 0) "" else ".$suffix") }
            .first { !it.exists() }
        try {
            if (!file.renameTo(backup)) {
                file.copyTo(backup, overwrite = false)
                if (!file.delete()) {
                    runCatching { backup.delete() }
                    throw IllegalStateException("Could not remove the original pin store after backup")
                }
            }
            backup
        } catch (e: Exception) {
            throw PinStoreException("Could not reset SSH host-key pin store", e)
        }
    }

    private fun loadNormalized(): LinkedHashMap<String, ByteArray> {
        if (!file.exists()) return linkedMapOf()
        try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val normalized = linkedMapOf<String, ByteArray>()
            var migrated = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawAlias = obj.getString("host")
                val alias = SshHostAlias.canonicalAlias(rawAlias)
                val key = Base64.getDecoder().decode(obj.getString("key"))
                require(key.isNotEmpty()) { "Empty SSH host key for $rawAlias" }
                val existing = normalized[alias]
                if (existing != null && !MessageDigest.isEqual(existing, key)) {
                    throw IllegalStateException("Conflicting SSH host-key pins normalize to $alias")
                }
                normalized[alias] = key
                migrated = migrated || alias != rawAlias
            }
            if (migrated) save(normalized)
            return normalized
        } catch (e: Exception) {
            throw PinStoreException("SSH host-key pin store is unreadable", e)
        }
    }

    private fun save(pins: Map<String, ByteArray>) {
        try {
            val array = JSONArray()
            pins.toSortedMap().forEach { (alias, key) ->
                array.put(JSONObject().apply {
                    put("host", alias)
                    put("key", Base64.getEncoder().encodeToString(key))
                })
            }
            AtomicFileWriter.writeText(file, array.toString(2))
        } catch (e: PinStoreException) {
            throw e
        } catch (e: Exception) {
            throw PinStoreException("Could not write SSH host-key pin store", e)
        }
    }

    class PinStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        val FILE_LOCK = Any()
    }
}
