package com.example.sshlink

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

class SettingsActivity : Activity() {
    private lateinit var settings: SettingsRepository
    private lateinit var keys: OpenSshEd25519KeyManager

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var intervalEdit: EditText
    private lateinit var countEdit: EditText
    private lateinit var publicKeyView: TextView
    private lateinit var keyStatusView: TextView
    private lateinit var batteryStatusView: TextView
    private lateinit var forwardContainer: LinearLayout
    private lateinit var exportButton: Button
    private lateinit var saveButton: Button
    private val forwardRows = mutableListOf<ForwardRow>()
    private var configRecoveryMode = false
    private var currentAppSettings = AppSettings()

    override fun onResume() {
        super.onResume()
        if (::batteryStatusView.isInitialized) refreshBatteryStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        keys = OpenSshEd25519KeyManager(this)
        setContentView(buildUi())
        if (savedInstanceState != null) {
            restoreUiState(savedInstanceState)
            setConfigRecoveryMode(savedInstanceState.getBoolean(STATE_CONFIG_RECOVERY_MODE))
        } else {
            try {
                loadIntoUi(settings.load())
                setConfigRecoveryMode(false)
            } catch (_: ConfigStore.ConfigCorruptedException) {
                loadIntoUi(AppConfig())
                setConfigRecoveryMode(true)
                showCorruptConfigDialog()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SSH_HOST, hostEdit.text.toString())
        outState.putString(STATE_SSH_PORT, portEdit.text.toString())
        outState.putString(STATE_USERNAME, userEdit.text.toString())
        outState.putString(STATE_KEEPALIVE_INTERVAL, intervalEdit.text.toString())
        outState.putString(STATE_KEEPALIVE_COUNT, countEdit.text.toString())
        outState.putStringArrayList(
            STATE_FORWARD_NAMES,
            ArrayList(forwardRows.map { it.name.text.toString() }),
        )
        outState.putStringArrayList(
            STATE_FORWARD_LOCAL_PORTS,
            ArrayList(forwardRows.map { it.local.text.toString() }),
        )
        outState.putStringArrayList(
            STATE_FORWARD_DESTINATIONS,
            ArrayList(forwardRows.map { it.destination.text.toString() }),
        )
        outState.putInt(STATE_LOG_LIMIT, currentAppSettings.logLimit)
        outState.putBoolean(STATE_CONFIG_RECOVERY_MODE, configRecoveryMode)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        content.addView(title("SSH Settings"))
        hostEdit = field("SSH Host", false)
        portEdit = field("SSH Port", true)
        userEdit = field("Username", false)
        intervalEdit = field("ServerAliveInterval (sec, 0=off)", true)
        countEdit = field("ServerAliveCountMax", true)
        content.addView(hostEdit)
        content.addView(portEdit)
        content.addView(userEdit)
        content.addView(intervalEdit)
        content.addView(countEdit)
        content.addView(Button(this).apply {
            text = "Forget pinned SSH host key"
            setOnClickListener { confirmForgetHostKey() }
        })
        content.addView(Button(this).apply {
            text = "Reset all pinned SSH host keys"
            setOnClickListener { confirmResetAllHostKeys() }
        })

        content.addView(title("Always-on operation"))
        batteryStatusView = TextView(this).apply { setPadding(0, 0, 0, dp(6)) }
        content.addView(batteryStatusView)
        content.addView(TextView(this).apply {
            text = "Android Doze can suspend network access. This app requires battery-optimization exemption for an always-on tunnel and holds a partial wake lock only while the tunnel service is running."
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(Button(this).apply {
            text = "Open power settings"
            setOnClickListener { BatteryOptimizationHelper.openRelevantSettings(this@SettingsActivity) }
        })

        content.addView(title("SSH Key"))
        keyStatusView = TextView(this).apply { setPadding(0, 0, 0, dp(6)) }
        content.addView(keyStatusView)
        content.addView(TextView(this).apply { text = "Key id: default (Ed25519 / OpenSSH format)" })
        publicKeyView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(publicKeyView)
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val buttonParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            addView(Button(this@SettingsActivity).apply {
                text = "Generate / Regenerate"
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setOnClickListener { confirmGenerateKey() }
            }, buttonParams)
            addView(Button(this@SettingsActivity).apply {
                text = "Copy public key"
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setOnClickListener { copyPublicKey() }
            }, buttonParams)
        })

        content.addView(title("Local Forwards"))
        content.addView(TextView(this).apply {
            text = "Local bind address is fixed to 127.0.0.1. Local Port must be 1024–65535. Enter each destination as host:port, for example 192.168.1.1:3389. The host is resolved on the SSH server side."
            setPadding(0, 0, 0, dp(8))
        })
        forwardContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(forwardContainer)
        content.addView(Button(this).apply {
            text = "Add Forward"
            setOnClickListener { addForwardRow(null) }
        })

        content.addView(title("Backup"))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            exportButton = Button(this@SettingsActivity).apply {
                text = "Export"
                setOnClickListener { exportConfig() }
            }
            addView(exportButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@SettingsActivity).apply {
                text = "Import"
                setOnClickListener { importConfig() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        content.addView(TextView(this).apply {
            text = "Private key and pinned SSH host key are intentionally excluded from Export."
            setPadding(0, dp(4), 0, dp(12))
        })

        saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { saveFromUi() }
        }
        content.addView(saveButton)

        return ScrollView(this).apply {
            addView(content)
            SystemBarInsets.apply(this)
        }
    }

    private fun loadIntoUi(config: AppConfig) {
        currentAppSettings = config.app
        hostEdit.setText(config.ssh.host)
        portEdit.setText(config.ssh.port.toString())
        userEdit.setText(config.ssh.username)
        intervalEdit.setText(config.ssh.keepAliveIntervalSec.toString())
        countEdit.setText(config.ssh.keepAliveCountMax.toString())
        forwardRows.clear()
        forwardContainer.removeAllViews()
        config.forwards.forEach { addForwardRow(it) }
        refreshKeyViews()
    }

    private fun restoreUiState(state: Bundle) {
        currentAppSettings = AppSettings(logLimit = state.getInt(STATE_LOG_LIMIT, 500))
        hostEdit.setText(state.getString(STATE_SSH_HOST).orEmpty())
        portEdit.setText(state.getString(STATE_SSH_PORT).orEmpty())
        userEdit.setText(state.getString(STATE_USERNAME).orEmpty())
        intervalEdit.setText(state.getString(STATE_KEEPALIVE_INTERVAL).orEmpty())
        countEdit.setText(state.getString(STATE_KEEPALIVE_COUNT).orEmpty())

        forwardRows.clear()
        forwardContainer.removeAllViews()
        val names = state.getStringArrayList(STATE_FORWARD_NAMES).orEmpty()
        val localPorts = state.getStringArrayList(STATE_FORWARD_LOCAL_PORTS).orEmpty()
        val destinations = state.getStringArrayList(STATE_FORWARD_DESTINATIONS).orEmpty()
        localPorts.indices.forEach { index ->
            addForwardRow(
                name = names.getOrElse(index) { "" },
                localPort = localPorts[index],
                destination = destinations.getOrElse(index) { "" },
            )
        }
        refreshKeyViews()
    }

    private fun setConfigRecoveryMode(enabled: Boolean) {
        configRecoveryMode = enabled
        saveButton.isEnabled = !enabled
        exportButton.isEnabled = !enabled
    }

    private fun showCorruptConfigDialog() {
        AlertDialog.Builder(this)
            .setTitle("Configuration recovery required")
            .setMessage("The saved configuration is unreadable or invalid. The existing file has not been changed. Save and Export are disabled to prevent accidental data loss. Import a valid backup to recover the configuration.")
            .setPositiveButton("Import backup") { _, _ -> importConfig() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveFromUi() {
        if (configRecoveryMode) {
            showCorruptConfigDialog()
            return
        }
        try {
            val config = AppConfig(
                ssh = SshConfig(
                    host = hostEdit.text.toString().trim(),
                    port = portEdit.text.toString().toInt(),
                    username = userEdit.text.toString().trim(),
                    keyId = "default",
                    keepAliveIntervalSec = intervalEdit.text.toString().toInt(),
                    keepAliveCountMax = countEdit.text.toString().toInt(),
                ),
                forwards = forwardRows.mapIndexed { index, row ->
                    val destination = try {
                        ForwardDestination.parse(row.destination.text.toString())
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Forward ${index + 1}: ${e.message}", e)
                    }
                    ForwardConfig(
                        localPort = row.local.text.toString().toInt(),
                        remoteHost = destination.host,
                        remotePort = destination.port,
                        name = row.name.text.toString().trim(),
                    )
                },
                app = currentAppSettings,
            )
            val errors = config.validationErrors()
            if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("\n"))
            settings.save(config)
            currentAppSettings = config.app
            TunnelState.log("INFO", "Settings saved")
            TunnelService.reconnect(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: ConfigStore.ConfigCorruptedException) {
            setConfigRecoveryMode(true)
            showCorruptConfigDialog()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Invalid settings")
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun addForwardRow(config: ForwardConfig?) {
        addForwardRow(
            name = config?.name.orEmpty(),
            localPort = config?.localPort?.toString().orEmpty(),
            destination = config?.let { ForwardDestination(it.remoteHost, it.remotePort).displayText() }.orEmpty(),
        )
    }

    private fun addForwardRow(name: String, localPort: String, destination: String) {
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        val nameEdit = field("Name (optional)", false).apply { setText(name) }
        val local = field("Local Port (1024-65535)", true).apply { setText(localPort) }
        val destinationEdit = field("Destination (host:port)", false).apply {
            setText(destination)
        }
        val remove = Button(this).apply { text = "Remove Forward" }
        rowLayout.addView(nameEdit)
        rowLayout.addView(local)
        rowLayout.addView(destinationEdit)
        rowLayout.addView(remove)
        val row = ForwardRow(rowLayout, nameEdit, local, destinationEdit)
        forwardRows += row
        remove.setOnClickListener {
            forwardRows.remove(row)
            forwardContainer.removeView(rowLayout)
        }
        forwardContainer.addView(rowLayout)
    }

    private fun confirmForgetHostKey() {
        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(this, "Enter a valid SSH Host and Port first", Toast.LENGTH_SHORT).show()
            return
        }
        val alias = try {
            SshHostAlias.canonical(host, port)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Invalid SSH Host: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Forget pinned host key?")
            .setMessage("Use this only after independently confirming that the SSH server host key was intentionally changed. The next successful connection will pin the newly observed key for $alias.")
            .setPositiveButton("Forget") { _, _ ->
                val repo = PinnedHostKeyRepository(this, { _, _ -> }, { _, _ -> })
                try {
                    val removed = repo.forget(alias)
                    Toast.makeText(this, if (removed) "Pinned host key forgotten" else "No pin stored for this host", Toast.LENGTH_SHORT).show()
                    if (removed) {
                        TunnelState.log("WARN", "Pinned SSH host key forgotten for $alias")
                        TunnelService.reconnect(this@SettingsActivity)
                    }
                } catch (e: HostKeyPinStore.PinStoreException) {
                    showPinStoreRecoveryDialog(e)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmResetAllHostKeys() {
        AlertDialog.Builder(this)
            .setTitle("Reset all pinned SSH host keys?")
            .setMessage("This removes all remembered SSH server identities. The existing pin database is preserved in app-private storage before reset. On the next successful connection, verify the server identity independently before relying on the new TOFU pin.")
            .setPositiveButton("Reset all") { _, _ -> resetAllHostKeys() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPinStoreRecoveryDialog(error: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Host key database recovery required")
            .setMessage("The pinned SSH host-key database is unreadable and verification is failing closed. You can preserve the corrupted file internally and reset all host-key pins without deleting the SSH private key.\n\n${error.message ?: error.javaClass.simpleName}")
            .setPositiveButton("Reset all pins") { _, _ -> confirmResetAllHostKeys() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAllHostKeys() {
        val repo = PinnedHostKeyRepository(this, { _, _ -> }, { _, _ -> })
        try {
            val backup = repo.resetAllPinsPreservingBackup()
            val detail = if (backup == null) "No pin database existed; trust state is empty" else "Previous pin database preserved internally as ${backup.name}"
            TunnelState.log("WARN", "All SSH host-key pins reset. $detail")
            Toast.makeText(this, "All pinned host keys reset", Toast.LENGTH_SHORT).show()
            TunnelService.reconnect(this)
        } catch (e: HostKeyPinStore.PinStoreException) {
            AlertDialog.Builder(this)
                .setTitle("Host key reset failed")
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun refreshBatteryStatus() {
        val block = BatteryOptimizationHelper.blockingReason(this)
        batteryStatusView.text = if (block == null) {
            "Power policy: standard Android checks allow always-on mode"
        } else {
            "Power policy blocks Start: $block"
        }
    }

    private fun confirmGenerateKey() {
        if (!keys.hasPrivateKey()) {
            generateKey()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Regenerate Ed25519 key?")
            .setMessage("The current private key will be replaced. The new public key must be installed on the SSH server before authentication will work.")
            .setPositiveButton("Regenerate") { _, _ -> generateKey() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateKey() {
        try {
            keys.generate()
            TunnelState.log("INFO", "Ed25519 key generated")
            refreshKeyViews()
            Toast.makeText(this, "Key generated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Key generation failed")
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun refreshKeyViews() {
        keyStatusView.text = if (keys.hasPrivateKey()) "Private key: present (app internal storage)" else "Private key: missing"
        publicKeyView.text = runCatching { keys.publicKeyText() }
            .getOrElse { "Public key unavailable: ${it.message ?: it.javaClass.simpleName}" }
            ?: "Public key: not generated"
    }

    private fun copyPublicKey() {
        val key = try {
            keys.publicKeyText()
        } catch (e: Exception) {
            Toast.makeText(this, "Public key unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (key == null) {
            Toast.makeText(this, "Generate a key first", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("SSHLink public key", key))
        Toast.makeText(this, "Public key copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportConfig() {
        if (configRecoveryMode) {
            showCorruptConfigDialog()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "sshlink-config.json")
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Legacy Activity result API keeps this project dependency-free from AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> writeUri(uri, settings.exportJson())
                REQUEST_IMPORT -> {
                    val imported = settings.importJson(readUri(uri))
                    loadIntoUi(imported)
                    setConfigRecoveryMode(false)
                    TunnelState.log("INFO", "Settings imported")
                    TunnelService.reconnect(this)
                    if (imported.hasMeaningfulSettings()) {
                        AlertDialog.Builder(this)
                            .setTitle("SSH key regeneration required")
                            .setMessage("The backup does not contain the private key. Regenerate the SSH key in Settings, then install the new public key on the SSH server.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            Toast.makeText(this, if (requestCode == REQUEST_EXPORT) "Exported" else "Imported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Backup error")
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun writeUri(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private fun readUri(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: error("Could not open import file")
        return input.buffered().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > ConfigLimits.MAX_IMPORT_BYTES) {
                    throw IllegalArgumentException("Import exceeds ${ConfigLimits.MAX_IMPORT_BYTES} bytes")
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun field(hintText: String, numeric: Boolean) = EditText(this).apply {
        hint = hintText
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ForwardRow(
        val container: View,
        val name: EditText,
        val local: EditText,
        val destination: EditText,
    )

    companion object {
        private const val REQUEST_EXPORT = 100
        private const val REQUEST_IMPORT = 101
        private const val STATE_SSH_HOST = "state.sshHost"
        private const val STATE_SSH_PORT = "state.sshPort"
        private const val STATE_USERNAME = "state.username"
        private const val STATE_KEEPALIVE_INTERVAL = "state.keepAliveInterval"
        private const val STATE_KEEPALIVE_COUNT = "state.keepAliveCount"
        private const val STATE_FORWARD_NAMES = "state.forwardNames"
        private const val STATE_FORWARD_LOCAL_PORTS = "state.forwardLocalPorts"
        private const val STATE_FORWARD_DESTINATIONS = "state.forwardDestinations"
        private const val STATE_LOG_LIMIT = "state.logLimit"
        private const val STATE_CONFIG_RECOVERY_MODE = "state.configRecoveryMode"
    }
}
