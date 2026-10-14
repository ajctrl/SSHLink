package com.example.sshlink

import android.app.Activity
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.EditText
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

class ReconnectRejectingApplication : Application() {
    var rejectServiceStart = true
    override fun startForegroundService(service: Intent): ComponentName? {
        if (rejectServiceStart) throw ForegroundServiceStartNotAllowedException("background start denied")
        return super.startForegroundService(service)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReconnectRejectingApplication::class)
class SettingsImportReconnectFailureTest {
    @Test fun committedImportReplacesOldDraftDespiteBackgroundReconnectDenial() = checkImport(false, false)
    @Test fun committedImportExitsCorruptConfigRecoveryDespiteReconnectDenial() = checkImport(true, false)
    @Test fun missingKeyAndReconnectDenialAreBothReportedAfterSuccessfulImport() = checkImport(false, true)

    private fun checkImport(corruptOriginal: Boolean, missingKey: Boolean) {
        val context = RuntimeEnvironment.getApplication() as ReconnectRejectingApplication
        val settings = SettingsRepository(context)
        val original = AppConfig(ssh = SshConfig("old.example.com", username = "alice"))
        val imported = AppConfig(ssh = SshConfig("new.example.com", username = "alice"))
        settings.save(original)
        if (corruptOriginal) File(context.filesDir, "config.json").writeText("{invalid")
        val keys = OpenSshEd25519KeyManager(context)
        if (missingKey) keys.privateKeyFile.delete() else keys.generate()
        settings.setTunnelDesired(true)
        settings.setRetryBlocked(true, "previous authentication failure")
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://sshlink-test/reconnect-denied.json")
        shadowOf(context.contentResolver).registerInputStream(uri,
            object : java.io.ByteArrayInputStream(imported.toJson().toString().toByteArray()) {
                override fun read(bytes: ByteArray, off: Int, length: Int): Int {
                    check(release.await(5, TimeUnit.SECONDS))
                    return super.read(bytes, off, length)
                }
            })
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            SettingsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                .invoke(activity, 101, Activity.RESULT_OK, Intent().setData(uri))
            controller.pause().stop()
            release.countDown()
            val operation = requireNotNull(field(activity, "backupOperation"))
            val result = operation.javaClass.getDeclaredField("result").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (result.get(operation) == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertNotNull(result.get(operation))
            assertEquals(imported, settings.load())
            // A failed service start must not silently clear the durable retry block.
            assertTrue(settings.isRetryBlocked())
            controller.restart().start().resume()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("Imported; reconnect required", shadowOf(dialog).title.toString())
            assertTrue(shadowOf(dialog).message.toString().contains("Settings were imported and saved"))
            assertEquals(missingKey, shadowOf(dialog).message.toString().contains("No valid private key"))
            val host = field(activity, "hostEdit") as EditText
            assertEquals("new.example.com", host.text.toString())
            assertTrue(host.isEnabled)
            assertEquals(false, field(activity, "configRecoveryMode"))
            assertNull(field(activity, "backupOperation"))
            assertEquals("Imported", ShadowToast.getTextOfLatestToast())
            // Saving the returned screen must not undo the committed import.
            context.rejectServiceStart = false
            SettingsActivity::class.java.getDeclaredMethod("saveFromUi").apply { isAccessible = true }.invoke(activity)
            assertEquals(imported, settings.load())
            assertEquals(TunnelService.ACTION_RECONNECT, shadowOf(context).nextStartedService.action)
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
    }

    private fun field(activity: SettingsActivity, name: String): Any? = SettingsActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)
}
