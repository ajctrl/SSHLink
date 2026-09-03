package com.example.sshlink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class TunnelService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private lateinit var settings: SettingsRepository
    private lateinit var keys: OpenSshEd25519KeyManager
    private lateinit var connectivity: ConnectivityManager

    private val connections = ConnectionGenerationState<Session>()
    @Volatile private var currentNetwork: Network? = null
    private val reconnectBackoff = ReconnectBackoffState()
    @Volatile private var runtimeRetryBlocked = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var monitorFuture: ScheduledFuture<*>? = null
    private var callbackRegistered = false
    private var powerSignalReceiverRegistered = false

    private val powerSignalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> runCatching {
                    executor.execute { reevaluatePowerPolicy("power state changed: $action") }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = synchronized(lock) {
                if (currentNetwork == network) false else {
                    currentNetwork = network
                    true
                }
            }
            if (changed && shouldAutomaticallyReconnect()) {
                TunnelState.log("INFO", "Default network changed; reconnecting and resolving DNS again")
                requestReconnect("network changed", 300)
            }
        }

        override fun onLost(network: Network) {
            val lostCurrent = synchronized(lock) {
                if (currentNetwork == network) {
                    currentNetwork = null
                    true
                } else false
            }
            if (lostCurrent && shouldAutomaticallyReconnect()) {
                TunnelState.log("WARN", "Default network lost")
                requestReconnect("network lost", 300)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        keys = OpenSshEd25519KeyManager(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
        currentNetwork = connectivity.activeNetwork
        runtimeRetryBlocked = settings.isRetryBlocked()
        configureJschForAndroid()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopTunnel(clearDesired = true)
            return START_NOT_STICKY
        }

        // Every path that can be entered through startForegroundService() promotes
        // immediately, before consulting persisted state. This removes the race
        // where Stop happens between reconnect() and onStartCommand().
        startForeground(NOTIFICATION_ID, buildNotification())

        when (action) {
            ACTION_START -> {
                settings.setTunnelDesired(true)
                settings.setRetryBlocked(false)
                runtimeRetryBlocked = false
                ensureRunning("start requested")
            }
            ACTION_RECONNECT -> {
                if (!settings.isTunnelDesired()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                settings.setRetryBlocked(false)
                runtimeRetryBlocked = false
                ensureRunning("settings changed")
            }
            ACTION_RESTORE -> {
                val result = handlePersistedStart(
                    startId = startId,
                    reason = intent.getStringExtra(EXTRA_RESTORE_REASON).orEmpty().ifBlank { "persisted tunnel restored" },
                )
                if (result != null) return result
            }
            null -> {
                val result = handlePersistedStart(startId, "service restarted")
                if (result != null) return result
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePersistedStart(startId: Int, reason: String): Int? {
        runtimeRetryBlocked = settings.isRetryBlocked()
        return when (TunnelLifecyclePolicy.restartDecision(
            desired = settings.isTunnelDesired(),
            retryBlocked = runtimeRetryBlocked,
        )) {
            TunnelLifecyclePolicy.RestartDecision.STOP_NOT_DESIRED -> {
                TunnelState.setDesired(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                START_NOT_STICKY
            }
            TunnelLifecyclePolicy.RestartDecision.STOP_RETRY_BLOCKED -> {
                val blockedReason = settings.retryBlockedReason().ifBlank { "Manual action required" }
                TunnelState.setDesired(true)
                setState(TunnelState.Status.ERROR, blockedReason)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                START_NOT_STICKY
            }
            TunnelLifecyclePolicy.RestartDecision.RUN -> {
                runtimeRetryBlocked = false
                ensureRunning(reason)
                null
            }
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        unregisterPowerSignalReceiver()
        reconnectFuture?.cancel(true)
        monitorFuture?.cancel(true)
        disconnectInvalidated(connections.invalidateAll())
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureRunning(reason: String) {
        TunnelState.setDesired(true)
        BatteryOptimizationHelper.blockingReason(this)?.let { reason ->
            powerPolicyFailure(reason)
            return
        }
        acquireWakeLock()
        registerNetworkCallback()
        registerPowerSignalReceiver()
        if (monitorFuture == null || monitorFuture?.isCancelled == true || monitorFuture?.isDone == true) {
            monitorFuture = executor.scheduleAtFixedRate({ monitorConnection() }, 5, 5, TimeUnit.SECONDS)
        }
        requestReconnect(reason, 0)
    }

    private fun shouldAutomaticallyReconnect(): Boolean =
        TunnelLifecyclePolicy.shouldAutomaticallyReconnect(
            desired = TunnelState.desiredRunning,
            retryBlocked = runtimeRetryBlocked,
            statusIsError = TunnelState.status == TunnelState.Status.ERROR,
        )

    private fun requestReconnect(reason: String, delayMs: Long) {
        val invalidated = synchronized(lock) {
            if (!TunnelState.desiredRunning || runtimeRetryBlocked) return
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            // A new network/explicit reconnect is a new failure domain; keep
            // exponential backoff only within retries of the same generation.
            reconnectBackoff.newGeneration()
            connections.nextGeneration()
        }
        disconnectInvalidated(invalidated)
        if (!isCurrent(invalidated.generation)) return
        setState(TunnelState.Status.CONNECTING, "Reconnecting: $reason")
        val scheduled = executor.schedule({ connect(invalidated.generation) }, delayMs, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (isCurrent(invalidated.generation)) reconnectFuture = scheduled else scheduled.cancel(false)
        }
    }

    private fun connect(gen: Long) {
        if (!isCurrent(gen)) return
        val config = try {
            settings.load()
        } catch (e: ConfigStore.ConfigCorruptedException) {
            terminalFailure(gen, "Configuration is unreadable or invalid. Import a valid backup in Settings.")
            return
        }
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            terminalFailure(gen, "Configuration error: ${errors.joinToString("; ")}")
            return
        }
        if (!keys.hasPrivateKey()) {
            terminalFailure(gen, "Private key is missing. Generate an Ed25519 key in Settings.")
            return
        }
        if (!keys.validatePrivateKey()) {
            terminalFailure(gen, "Private key is invalid or is not a supported unencrypted OpenSSH Ed25519 key.")
            return
        }

        val connectionHost = try {
            SshHostAlias.canonicalHost(config.ssh.host)
        } catch (e: Exception) {
            terminalFailure(gen, "Invalid SSH host: ${e.message}")
            return
        }
        val alias = SshHostAlias.canonical(connectionHost, config.ssh.port)

        setState(TunnelState.Status.CONNECTING, "Connecting to ${config.ssh.host}:${config.ssh.port}")
        TunnelState.log(
            "INFO",
            "SSH connect start: ${config.ssh.username}@${config.ssh.host}:${config.ssh.port}",
            config.app.logLimit,
        )

        var newSession: Session? = null
        val connectionHostKeys = PinnedHostKeyRepository(
            this,
            onPinned = { host, fp -> TunnelState.log("INFO", "Pinned SSH host key for $host ($fp)") },
            onChanged = { host, fp -> TunnelState.log("ERROR", "SSH host key changed for $host; rejected ($fp)") },
        )
        try {
            val jsch = JSch()
            jsch.setHostKeyRepository(connectionHostKeys)
            jsch.addIdentity(keys.privateKeyFile.absolutePath)

            // Refresh the snapshot for every attempt. This also keeps retries
            // usable if default-network callback registration is unavailable.
            val network = synchronized(lock) {
                connectivity.activeNetwork.also { currentNetwork = it }
            }
            val candidate = jsch.getSession(config.ssh.username, connectionHost, config.ssh.port).apply {
                SshSessionPolicy.apply(this)
                setHostKeyAlias(alias)
                setServerAliveInterval(config.ssh.keepAliveIntervalSec * 1000)
                setServerAliveCountMax(config.ssh.keepAliveCountMax)
                setSocketFactory(
                    AndroidNetworkSocketFactory(network, CONNECT_TIMEOUT_MS) { addresses ->
                        TunnelState.log(
                            "INFO",
                            "DNS ${config.ssh.host} -> ${addresses.joinToString()}",
                            config.app.logLimit,
                        )
                    }
                )
            }
            newSession = candidate
            if (!connections.registerInFlight(gen, candidate) || !isCurrent(gen)) {
                candidate.disconnect()
                return
            }
            candidate.connect(CONNECT_TIMEOUT_MS)

            // First-seen host keys are staged during the handshake. Commit trust only
            // while this connection generation is still current, so an obsolete
            // concurrent attempt cannot mutate the persistent TOFU pin store.
            val trustCommitted = connections.runIfInFlight(gen, candidate) {
                if (!TunnelState.desiredRunning || runtimeRetryBlocked) {
                    throw ObsoleteConnectionException()
                }
                connectionHostKeys.commitPending()
            }
            if (!trustCommitted || !isCurrent(gen)) {
                connectionHostKeys.discardPending()
                candidate.disconnect()
                return
            }

            val active = config.forwards.map { forward ->
                candidate.setPortForwardingL(
                    "127.0.0.1",
                    forward.localPort,
                    forward.remoteHost,
                    forward.remotePort,
                )
                val name = forward.name.takeIf(String::isNotBlank)?.let { "[$it] " }.orEmpty()
                "${name}127.0.0.1:${forward.localPort} -> ${forward.remoteHost}:${forward.remotePort}"
            }

            if (!isCurrent(gen) || !connections.promote(gen, candidate)) {
                candidate.disconnect()
                return
            }
            reconnectBackoff.reset()
            TunnelState.setActiveForwards(active)
            active.forEach { TunnelState.log("INFO", "Forward active: $it", config.app.logLimit) }
            setState(TunnelState.Status.CONNECTED, "Connected")
            TunnelState.log("INFO", "SSH connected", config.app.logLimit)
        } catch (e: Exception) {
            connectionHostKeys.discardPending()
            newSession?.let { connections.clear(it) }
            runCatching { newSession?.disconnect() }
            if (!isCurrent(gen) || e is ObsoleteConnectionException) return
            val message = e.message ?: e.javaClass.simpleName
            when (ReconnectPolicy.classify(e)) {
                ReconnectPolicy.FailureKind.TERMINAL -> terminalFailure(gen, "SSH failed: $message", config.app.logLimit)
                ReconnectPolicy.FailureKind.RETRYABLE -> {
                    TunnelState.log("WARN", "SSH disconnected/failed: $message", config.app.logLimit)
                    scheduleRetry(gen, message, config.app.logLimit)
                }
            }
        }
    }

    private fun scheduleRetry(gen: Long, reason: String, logLimit: Int = 500) {
        if (!isCurrent(gen)) return
        val attempt = reconnectBackoff.nextAttempt()
        val seconds = ReconnectPolicy.retryDelaySeconds(attempt)
        setState(TunnelState.Status.RECONNECT_WAIT, "Retry in ${seconds}s: $reason")
        TunnelState.log("INFO", "Reconnect scheduled in ${seconds}s", logLimit)
        val scheduled = executor.schedule({ connect(gen) }, seconds, TimeUnit.SECONDS)
        synchronized(lock) {
            if (isCurrent(gen)) reconnectFuture = scheduled else scheduled.cancel(false)
        }
    }

    private fun monitorConnection() {
        if (!shouldAutomaticallyReconnect()) return
        if (!reevaluatePowerPolicy("periodic power-policy check")) return
        if (!callbackRegistered) {
            val latest = connectivity.activeNetwork
            val changed = synchronized(lock) {
                if (currentNetwork == latest) false else {
                    currentNetwork = latest
                    true
                }
            }
            if (changed) {
                TunnelState.log("INFO", "Default network changed; reconnecting via polling fallback")
                requestReconnect("network changed", 0)
                return
            }
        }
        if (TunnelState.status == TunnelState.Status.CONNECTED && connections.active()?.isConnected != true) {
            TunnelState.log("WARN", "SSH disconnect detected")
            requestReconnect("disconnect detected", 0)
        }
    }

    private fun reevaluatePowerPolicy(trigger: String): Boolean {
        if (!shouldAutomaticallyReconnect()) return false
        val reason = BatteryOptimizationHelper.blockingReason(this) ?: return true
        powerPolicyFailure("Power policy changed while the tunnel was running ($trigger): $reason")
        return false
    }

    private fun powerPolicyFailure(message: String) {
        val invalidated = synchronized(lock) {
            if (runtimeRetryBlocked) return
            runtimeRetryBlocked = true
            reconnectFuture?.cancel(true)
            reconnectFuture = null
            connections.invalidateAll()
        }
        settings.setRetryBlocked(true, message)
        TunnelState.log("ERROR", message)
        setState(TunnelState.Status.ERROR, message)
        disconnectInvalidated(invalidated)
        unregisterNetworkCallback()
        unregisterPowerSignalReceiver()
        monitorFuture?.cancel(true)
        monitorFuture = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun terminalFailure(gen: Long, message: String, logLimit: Int = 500) {
        val invalidated = synchronized(lock) {
            if (!TunnelState.desiredRunning || runtimeRetryBlocked) return
            val result = connections.invalidateIfCurrent(gen) ?: return
            runtimeRetryBlocked = true
            reconnectFuture?.cancel(true)
            reconnectFuture = null
            result
        }
        settings.setRetryBlocked(true, message)
        TunnelState.log("ERROR", message, logLimit)
        setState(TunnelState.Status.ERROR, message)
        disconnectInvalidated(invalidated)
        unregisterNetworkCallback()
        unregisterPowerSignalReceiver()
        monitorFuture?.cancel(true)
        monitorFuture = null
        releaseWakeLock()
        // Keep the persisted "desired" flag so saving settings or pressing Start
        // can explicitly clear the retry block, but do not keep an idle FGS alive.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTunnel(clearDesired: Boolean) {
        if (clearDesired) {
            settings.setTunnelDesired(false)
            settings.setRetryBlocked(false)
        }
        TunnelState.setDesired(false)
        val invalidated = synchronized(lock) {
            runtimeRetryBlocked = false
            reconnectFuture?.cancel(true)
            reconnectFuture = null
            connections.invalidateAll()
        }
        disconnectInvalidated(invalidated)
        unregisterNetworkCallback()
        unregisterPowerSignalReceiver()
        monitorFuture?.cancel(true)
        monitorFuture = null
        releaseWakeLock()
        TunnelState.setActiveForwards(emptyList())
        TunnelState.setStatus(TunnelState.Status.STOPPED, "Stopped")
        TunnelState.log("INFO", "Tunnel stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disconnectInvalidated(invalidated: ConnectionGenerationState.Invalidated<Session>) {
        invalidated.connections.forEach { connection -> runCatching { connection.disconnect() } }
        TunnelState.setActiveForwards(emptyList())
    }

    private fun isCurrent(gen: Long): Boolean =
        TunnelState.desiredRunning && !runtimeRetryBlocked && connections.isCurrent(gen)

    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (e: Exception) {
            TunnelState.log("WARN", "Network callback registration failed; using periodic fallback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!callbackRegistered) return
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
    }


    private fun registerPowerSignalReceiver() {
        if (powerSignalReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(powerSignalReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(powerSignalReceiver, filter)
            }
            powerSignalReceiverRegistered = true
        } catch (e: Exception) {
            TunnelState.log("WARN", "Power-state receiver registration failed: ${e.message}")
        }
    }

    private fun unregisterPowerSignalReceiver() {
        if (!powerSignalReceiverRegistered) return
        runCatching { unregisterReceiver(powerSignalReceiver) }
        powerSignalReceiverRegistered = false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val existing = wakeLock
        val value = existing ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SSHLink")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!value.isHeld) value.acquire()
    }

    private fun releaseWakeLock() {
        val value = wakeLock ?: return
        if (value.isHeld) runCatching { value.release() }
    }

    private class ObsoleteConnectionException : Exception()

    private fun setState(status: TunnelState.Status, detail: String) {
        TunnelState.setStatus(status, detail)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SSHLink", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent SSH tunnel status"
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("SSHLink — ${TunnelState.status.displayName}")
            .setContentText(TunnelState.detail)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(TunnelState.desiredRunning)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun configureJschForAndroid() {
        // Defense in depth: session-level SshSessionPolicy applies the same auth
        // restrictions, while these defaults protect any future Session creation.
        JSch.setConfig("StrictHostKeyChecking", SshSessionPolicy.STRICT_HOST_KEY_CHECKING)
        JSch.setConfig("enable_auth_none", SshSessionPolicy.ENABLE_AUTH_NONE)
        JSch.setConfig("PreferredAuthentications", SshSessionPolicy.PREFERRED_AUTHENTICATIONS)
        JSch.setConfig("enable_pubkey_auth_query", SshSessionPolicy.ENABLE_PUBKEY_AUTH_QUERY)
        JSch.setConfig("PubkeyAcceptedAlgorithms", SshSessionPolicy.PUBKEY_ACCEPTED_ALGORITHMS)
        JSch.setConfig("NumberOfPasswordPrompts", SshSessionPolicy.NUMBER_OF_PASSWORD_PROMPTS)
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
    }

    companion object {
        const val ACTION_START = "com.example.sshlink.START"
        const val ACTION_STOP = "com.example.sshlink.STOP"
        const val ACTION_RECONNECT = "com.example.sshlink.RECONNECT"
        const val ACTION_RESTORE = "com.example.sshlink.RESTORE"
        private const val EXTRA_RESTORE_REASON = "restore_reason"
        private const val CHANNEL_ID = "sshlink"
        private const val NOTIFICATION_ID = 1001
        private const val CONNECT_TIMEOUT_MS = 12_000

        fun start(context: Context) {
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }

        fun reconnect(context: Context) {
            if (!SettingsRepository(context).isTunnelDesired()) return
            val intent = Intent(context, TunnelService::class.java).setAction(ACTION_RECONNECT)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun restorePersisted(context: Context, reason: String) {
            val repository = SettingsRepository(context)
            if (!repository.isTunnelDesired() || repository.isRetryBlocked()) return
            val intent = Intent(context, TunnelService::class.java)
                .setAction(ACTION_RESTORE)
                .putExtra(EXTRA_RESTORE_REASON, reason)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
