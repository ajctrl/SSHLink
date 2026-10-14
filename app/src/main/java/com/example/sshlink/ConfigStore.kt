package com.example.sshlink

import org.json.JSONObject
import java.io.File

/**
 * Fail-closed storage for the JSON configuration.
 *
 * A missing file means "not configured" and returns AppConfig(). An existing but
 * unreadable/invalid file is never silently treated as empty. Normal save refuses
 * to overwrite such a file; only an explicit validated import may replace it.
 */
class ConfigStore(private val configFile: File) {
    private val revisionKey = configFile.absoluteFile.normalize()

    class Revision internal constructor(internal val file: File, internal val value: Long)

    // No disk read: even a corrupt file must remain recoverable by an import.
    fun revision(): Revision = synchronized(FILE_LOCK) {
        Revision(revisionKey, revisions[revisionKey] ?: 0L)
    }

    fun load(): AppConfig = synchronized(FILE_LOCK) { loadUnlocked() }

    fun save(config: AppConfig) = synchronized(FILE_LOCK) {
        validateForStorage(config)
        if (configFile.exists()) {
            // Refuse to turn corruption into data loss. The caller must use importJson
            // for an explicit recovery/replacement operation.
            loadUnlocked()
        }
        AtomicFileWriter.writeText(configFile, config.toJson().toString(2))
        recordWrite()
    }

    fun exportJson(): String = synchronized(FILE_LOCK) {
        loadUnlocked().toJson().toString(2)
    }

    fun importJson(text: String, expectedRevision: Revision? = null): AppConfig = synchronized(FILE_LOCK) {
        if (expectedRevision != null && (expectedRevision.file != revisionKey ||
                expectedRevision.value != (revisions[revisionKey] ?: 0L))) {
            throw ConcurrentUpdateException()
        }
        require(text.toByteArray(Charsets.UTF_8).size <= ConfigLimits.MAX_IMPORT_BYTES) {
            "Import exceeds ${ConfigLimits.MAX_IMPORT_BYTES} bytes"
        }
        val config = parse(text)
        validateForStorage(config)
        // Import is the explicit recovery path and may replace a corrupt config.
        AtomicFileWriter.writeText(configFile, config.toJson().toString(2))
        recordWrite()
        config
    }

    private fun recordWrite() {
        revisions[revisionKey] = (revisions[revisionKey] ?: 0L) + 1
    }

    private fun loadUnlocked(): AppConfig {
        if (!configFile.exists()) return AppConfig()
        if (configFile.length() > ConfigLimits.MAX_IMPORT_BYTES) {
            throw ConfigCorruptedException("Configuration file exceeds ${ConfigLimits.MAX_IMPORT_BYTES} bytes", IllegalArgumentException("oversized configuration"))
        }
        return try {
            parse(configFile.readText(Charsets.UTF_8))
        } catch (e: ConfigCorruptedException) {
            throw e
        } catch (e: Exception) {
            throw ConfigCorruptedException("Configuration file is unreadable or invalid", e)
        }
    }

    private fun parse(text: String): AppConfig = try {
        AppConfig.fromJson(JSONObject(text))
    } catch (e: Exception) {
        throw ConfigCorruptedException("Configuration file is unreadable or invalid", e)
    }

    private fun validateForStorage(config: AppConfig) {
        val errors = config.validationErrors().filterNot {
            !config.hasMeaningfulSettings() && (it.startsWith("SSH Host") || it.startsWith("Username"))
        }
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }

    class ConfigCorruptedException(message: String, cause: Throwable) : Exception(message, cause)

    class ConcurrentUpdateException : Exception(
        "Settings changed while the backup was being read. The import was not applied. The latest saved settings are shown; select the backup again if you still want to replace them."
    )

    private companion object {
        val FILE_LOCK = Any()
        // All config writers share this process. A process restart also ends every
        // pending import, so these generations do not need to be persisted.
        val revisions = mutableMapOf<File, Long>()
    }
}
