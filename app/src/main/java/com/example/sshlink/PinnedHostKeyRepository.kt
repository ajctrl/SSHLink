package com.example.sshlink

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * TOFU repository with deferred first-pin persistence.
 *
 * An unknown host key is accepted for the current SSH handshake but remains only
 * in memory. TunnelService commits it after authentication succeeds and the
 * connection generation is still current. Obsolete/failed connection attempts
 * therefore cannot mutate persistent trust state.
 */
class PinnedHostKeyRepository(
    context: Context,
    private val onPinned: (host: String, fingerprint: String) -> Unit,
    private val onChanged: (host: String, fingerprint: String) -> Unit,
) : HostKeyRepository {
    private val file = File(context.applicationContext.filesDir, "ssh_host_pins.json")
    private val store = HostKeyPinStore(file)
    private val pendingLock = Any()
    private val pending = linkedMapOf<String, ByteArray>()

    override fun check(host: String, key: ByteArray): Int = try {
        val canonical = SshHostAlias.canonicalAlias(host)
        when (store.inspect(canonical, key)) {
            HostKeyPinStore.CheckResult.MISSING -> {
                synchronized(pendingLock) { pending[canonical] = key.copyOf() }
                HostKeyRepository.OK
            }
            HostKeyPinStore.CheckResult.MATCH -> HostKeyRepository.OK
            HostKeyPinStore.CheckResult.CHANGED -> {
                onChanged(canonical, fingerprint(key))
                HostKeyRepository.CHANGED
            }
            HostKeyPinStore.CheckResult.PINNED -> HostKeyRepository.OK // inspect() never returns PINNED
        }
    } catch (e: Exception) {
        TunnelState.log("ERROR", "Host key pin store is unreadable; SSH host verification fails closed: ${e.message}")
        HostKeyRepository.CHANGED
    }

    /** Commit all first-seen keys staged by this connection attempt. */
    fun commitPending() {
        val snapshot = synchronized(pendingLock) {
            pending.mapValues { (_, value) -> value.copyOf() }
        }
        snapshot.forEach { (alias, key) ->
            when (store.commit(alias, key)) {
                HostKeyPinStore.CheckResult.PINNED -> onPinned(alias, fingerprint(key))
                HostKeyPinStore.CheckResult.MATCH -> Unit
                HostKeyPinStore.CheckResult.CHANGED -> {
                    onChanged(alias, fingerprint(key))
                    throw HostKeyCommitException("SSH host key changed before trust could be committed for $alias")
                }
                HostKeyPinStore.CheckResult.MISSING -> error("commit() cannot return MISSING")
            }
        }
        synchronized(pendingLock) { pending.clear() }
    }

    fun discardPending() {
        synchronized(pendingLock) { pending.clear() }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        // JSch may call add() for an accepted key. Keep it connection-local until
        // TunnelService explicitly commits the current generation.
        val canonical = SshHostAlias.canonicalAlias(hostkey.host)
        val raw = Base64.getDecoder().decode(hostkey.key)
        synchronized(pendingLock) { pending[canonical] = raw }
    }

    override fun remove(host: String, type: String?) {
        runStoreOperation {
            store.remove(host) { raw ->
                val hk = runCatching { HostKey(SshHostAlias.canonicalAlias(host), raw) }.getOrNull()
                type == null || hk?.type == type
            }
        }
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        runStoreOperation {
            store.remove(host) { raw ->
                val hk = runCatching { HostKey(SshHostAlias.canonicalAlias(host), raw) }.getOrNull()
                (type == null || hk?.type == type) && (key == null || MessageDigest.isEqual(raw, key))
            }
        }
    }

    /** User-facing operation: propagate corruption so Settings can offer recovery. */
    fun forget(host: String): Boolean = store.forget(host)

    /** Preserve the old pin database and reset trust to an empty TOFU state. */
    fun resetAllPinsPreservingBackup(): File? = store.resetAllPreservingBackup()

    override fun getKnownHostsRepositoryID(): String = file.absolutePath

    override fun getHostKey(): Array<HostKey> = try {
        store.entries().mapNotNull { (host, raw) -> runCatching { HostKey(host, raw) }.getOrNull() }.toTypedArray()
    } catch (_: Exception) {
        emptyArray()
    }

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = try {
        val canonical = host?.let(SshHostAlias::canonicalAlias)
        store.entries().mapNotNull { (savedHost, raw) ->
            if (canonical != null && savedHost != canonical) return@mapNotNull null
            val hk = runCatching { HostKey(savedHost, raw) }.getOrNull() ?: return@mapNotNull null
            if (type != null && hk.type != type) return@mapNotNull null
            hk
        }.toTypedArray()
    } catch (_: Exception) {
        emptyArray()
    }

    private fun runStoreOperation(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            TunnelState.log("ERROR", "Could not update host key pin store: ${e.message}")
        }
    }

    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    class HostKeyCommitException(message: String) : SecurityException(message)
}
