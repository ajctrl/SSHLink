package com.example.sshlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var settings: SettingsRepository
    private lateinit var keys: OpenSshEd25519KeyManager
    private lateinit var statusView: TextView
    private lateinit var targetView: TextView
    private lateinit var forwardsView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var warnedMissingKey = false
    private var pendingStartAfterNotificationPermission = false
    private var lastLogVersion = -1L

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        keys = OpenSshEd25519KeyManager(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        refreshConfigDisplay()
        handler.post(refreshTask)
        maybeWarnMissingKey()
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        SystemBarInsets.apply(root)

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        statusView = TextView(this).apply { textSize = 18f; setPadding(0, dp(14), 0, dp(6)) }
        targetView = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        forwardsView = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(12)) }
        root.addView(statusView)
        root.addView(targetView)
        root.addView(forwardsView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            startButton = Button(this@MainActivity).apply {
                text = "Start"
                setOnClickListener { startTunnel() }
            }
            addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            stopButton = Button(this@MainActivity).apply {
                text = "Stop"
                isEnabled = false
                setOnClickListener { TunnelService.stop(this@MainActivity) }
            }
            addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "Settings"
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(TextView(this).apply {
            text = "Log"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun startTunnel() {
        val config = try {
            settings.load()
        } catch (e: ConfigStore.ConfigCorruptedException) {
            showConfigCorruption("Cannot start")
            return
        }
        val errors = config.validationErrors().toMutableList()
        if (!keys.hasPrivateKey()) {
            errors += "Ed25519 private key is missing"
        } else if (!keys.validatePrivateKey()) {
            errors += "Ed25519 private key is invalid or unsupported"
        }
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Cannot start")
                .setMessage(errors.joinToString("\n"))
                .setPositiveButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val powerBlock = BatteryOptimizationHelper.blockingReason(this)
        if (powerBlock != null) {
            AlertDialog.Builder(this)
                .setTitle("Power settings block always-on mode")
                .setMessage("$powerBlock\n\nOpen system power settings, remove the restriction, then press Start again.")
                .setPositiveButton("Power settings") { _, _ -> BatteryOptimizationHelper.openRelevantSettings(this) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        requestNotificationPermissionThenStart()
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterNotificationPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        TunnelService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS || !pendingStartAfterNotificationPermission) return
        pendingStartAfterNotificationPermission = false
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(this, "Notification permission denied; the tunnel can run, but its foreground notification may not appear in the notification drawer.", android.widget.Toast.LENGTH_LONG).show()
        }
        TunnelService.start(this)
    }

    private fun refresh() {
        val display = RuntimeStatePolicy.resolve(
            memoryStatus = TunnelState.status,
            memoryDetail = TunnelState.detail,
            memoryDesired = TunnelState.desiredRunning,
            persistedDesired = settings.isTunnelDesired(),
            retryBlocked = settings.isRetryBlocked(),
            retryReason = settings.retryBlockedReason(),
        )
        startButton.isEnabled = display.status == TunnelState.Status.STOPPED ||
            display.status == TunnelState.Status.ERROR
        stopButton.isEnabled = display.desired || display.status != TunnelState.Status.STOPPED
        statusView.text = "Status: ${display.status.displayName} — ${display.detail}"

        val snapshot = TunnelState.logsSnapshot()
        if (snapshot.version != lastLogVersion) {
            logView.text = snapshot.entries.joinToString("\n") { it.format() }
            lastLogVersion = snapshot.version
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refreshConfigDisplay() {
        try {
            val config = settings.load()
            targetView.text = if (config.ssh.host.isBlank()) {
                "SSH target: not configured"
            } else {
                "SSH target: ${config.ssh.username}@${config.ssh.host}:${config.ssh.port}"
            }
            forwardsView.text = if (config.forwards.isEmpty()) {
                "Forwards: none"
            } else {
                "Forwards:\n" + config.forwards.joinToString("\n") {
                    val destination = ForwardDestination(it.remoteHost, it.remotePort).displayText()
                    val name = it.name.takeIf(String::isNotBlank)?.let { value -> "[$value] " }.orEmpty()
                    "  ${name}127.0.0.1:${it.localPort} → $destination"
                }
            }
        } catch (_: ConfigStore.ConfigCorruptedException) {
            targetView.text = "SSH target: configuration unreadable"
            forwardsView.text = "Forwards: unavailable — import a valid backup in Settings"
        }
    }

    private fun maybeWarnMissingKey() {
        val config = try {
            settings.load()
        } catch (_: ConfigStore.ConfigCorruptedException) {
            return
        }
        if (!warnedMissingKey && config.hasMeaningfulSettings() && !keys.hasPrivateKey()) {
            warnedMissingKey = true
            AlertDialog.Builder(this)
                .setTitle("Private key missing")
                .setMessage("SSH settings exist, but the app-private Ed25519 key is missing. Generate or regenerate it in Settings before starting the tunnel.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun showConfigCorruption(title: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("The saved configuration is unreadable or invalid. It has not been replaced. Open Settings and import a valid backup to recover it.")
            .setPositiveButton("Settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 200
    }
}
