package com.example.sshlink

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import java.io.ByteArrayOutputStream

/** Retained across configuration changes; the worker only holds application context. */
internal class SettingsBackupOperation(context: Context, private val uri: Uri, val isImport: Boolean) {
    data class Outcome(val imported: AppConfig?, val reconnectError: Exception? = null)

    private val appContext = context.applicationContext
    private val settings = SettingsRepository(appContext)
    // Capture on the caller's thread, before scheduling the remote read. Another
    // screen can save or finish an import before this worker even starts.
    private val importRevision = if (isImport) settings.configRevision() else null
    private val main = Handler(Looper.getMainLooper())
    private val commitLock = Any()
    @Volatile private var importCancelled = false
    private var worker: Thread? = null
    // Accessed only on the main thread, including completion delivery.
    private var result: Result<Outcome>? = null
    private var listener: ((Result<Outcome>) -> Unit)? = null

    fun attach(listener: (Result<Outcome>) -> Unit) {
        this.listener = listener
        result?.let(listener)
    }

    fun detach() {
        listener = null
    }

    /** A finished screen must not commit an import after a later user Save. */
    fun cancelImport() {
        if (!isImport) return
        synchronized(commitLock) {
            importCancelled = true
        }
        // Some providers ignore interruption. The commit guard remains authoritative.
        worker?.interrupt()
    }

    fun start() {
        worker = Thread({
            val outcome = try {
                val completed = if (isImport) {
                    val text = readUri()
                    // Remote reads never hold this lock. Once cancellation returns,
                    // this worker cannot change settings or request a reconnect.
                    synchronized(commitLock) {
                        checkImportActive()
                        val imported = settings.importJson(text, importRevision)
                        TunnelState.log("INFO", "Settings imported")
                        // Persistence has succeeded. A denied service start must
                        // not turn that success into a failed import with stale UI.
                        val reconnectError = try {
                            TunnelService.reconnect(appContext)
                            null
                        } catch (e: Exception) {
                            TunnelState.log("WARN", "Settings imported, but reconnect could not start: ${e.message}")
                            e
                        }
                        Outcome(imported, reconnectError)
                    }
                } else {
                    val text = settings.exportJson()
                    val output = appContext.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Could not open export file")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    Outcome(null)
                }
                Result.success(completed)
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                result = outcome
                listener?.invoke(outcome)
            }
        }, "SSHLink-backup").also { it.start() }
    }

    private fun checkImportActive() {
        if (importCancelled) throw OperationCanceledException("Import cancelled")
    }

    private fun readUri(): String {
        checkImportActive()
        val input = appContext.contentResolver.openInputStream(uri) ?: error("Could not open import file")
        return input.buffered().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                checkImportActive()
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= ConfigLimits.MAX_IMPORT_BYTES) {
                    "Import exceeds ${ConfigLimits.MAX_IMPORT_BYTES} bytes"
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
}
