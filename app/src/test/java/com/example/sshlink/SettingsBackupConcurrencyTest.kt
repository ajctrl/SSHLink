package com.example.sshlink

import android.app.Activity
import android.app.Notification
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import java.io.ByteArrayInputStream
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsBackupConcurrencyTest {
    @Test fun notificationNavigationCannotLetOldImportOverwriteLaterSave() = checkScreens(false, false)
    @Test fun returningToCompletedImportShowsLaterSaveInsteadOfOldResult() = checkScreens(true, false)
    @Test fun laterCompletedImportInvalidatesOlderPendingImport() = checkScreens(false, true)

    private fun checkScreens(completeFirstBeforeSave: Boolean, secondImports: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.save(config("original.example.com"))
        settings.setTunnelDesired(false)
        OpenSshEd25519KeyManager(context).generate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://sshlink-test/slow-concurrent-import.json")
        shadowOf(context.contentResolver).registerInputStream(uri,
            object : ByteArrayInputStream(config("old-import.example.com").toJson().toString().toByteArray()) {
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(bytes, off, len)
                }
            })
        val first = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val service = Robolectric.buildService(TunnelService::class.java).create()
        val notification = TunnelService::class.java.getDeclaredMethod("buildNotification").apply { isAccessible = true }
            .invoke(service.get()) as Notification
        import(first.get(), uri)
        val operation = requireNotNull(field(first.get(), "backupOperation"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        notification.contentIntent.send()
        val openMain = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, openMain.component!!.className)
        assertEquals(0, openMain.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val mainInfo = context.packageManager.getActivityInfo(openMain.component!!, 0)
        assertEquals(android.content.pm.ActivityInfo.LAUNCH_MULTIPLE, mainInfo.launchMode)
        first.pause().stop()
        assertFalse(first.get().isFinishing)
        val main = Robolectric.buildActivity(MainActivity::class.java, openMain).create().start().resume()
        views(main.get().findViewById(android.R.id.content)).filterIsInstance<Button>()
            .single { it.text == "Settings" }.performClick()
        val openSettings = shadowOf(main.get()).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, openSettings.component!!.className)
        main.pause().stop()
        val second = Robolectric.buildActivity(SettingsActivity::class.java, openSettings).create().start().resume()
        var firstRestarted = false
        try {
            if (completeFirstBeforeSave) {
                release.countDown()
                awaitResult(operation)
                assertEquals("old-import.example.com", settings.load().ssh.host)
            }
            if (secondImports) {
                val laterUri = Uri.parse("content://sshlink-test/later-import.json")
                shadowOf(context.contentResolver).registerInputStream(laterUri,
                    config("later-save.example.com").toJson().toString().byteInputStream())
                import(second.get(), laterUri)
                awaitResult(requireNotNull(field(second.get(), "backupOperation")))
            } else {
                host(second.get()).setText("later-save.example.com")
                save(second.get())
            }
            assertEquals("later-save.example.com", settings.load().ssh.host)
            release.countDown()
            awaitResult(operation)
            assertEquals("later-save.example.com", settings.load().ssh.host)
            assertEquals("later-save.example.com", host(second.get()).text.toString())
            // Neither an abandoned import nor returning to its result should
            // request a reconnect with the obsolete settings.
            assertNull(shadowOf(context).nextStartedService)

            first.restart().start().resume()
            firstRestarted = true
            assertEquals("later-save.example.com", host(first.get()).text.toString())
            assertTrue(host(first.get()).isEnabled)
            assertNull(field(first.get(), "backupOperation"))
            if (!completeFirstBeforeSave) {
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                assertEquals("Import not applied", shadowOf(dialog).title.toString())
                assertTrue(shadowOf(dialog).message.toString().contains("Settings changed"))
                dialog.dismiss()
            }
            save(first.get())
            assertEquals("later-save.example.com", settings.load().ssh.host)
        } finally {
            release.countDown()
            second.pause().stop().destroy()
            main.destroy()
            if (firstRestarted) first.pause().stop()
            first.destroy()
            service.destroy()
        }
    }

    @Test fun settingsSavedAfterOperationCreationButBeforeWorkerStartInvalidateImport() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.save(config("original.example.com"))
        settings.setTunnelDesired(true)
        shadowOf(context).clearStartedServices()
        val uri = Uri.parse("content://sshlink-test/worker-not-started.json")
        shadowOf(context.contentResolver).registerInputStream(uri,
            config("old-import.example.com").toJson().toString().byteInputStream())
        val operation = SettingsBackupOperation(context, uri, true)
        settings.save(config("later-save.example.com"))
        var failure: Throwable? = null
        operation.attach { failure = it.exceptionOrNull() }
        operation.start()
        awaitResult(operation)
        assertTrue(failure is ConfigStore.ConcurrentUpdateException)
        assertEquals("later-save.example.com", settings.load().ssh.host)
        assertNull(shadowOf(context).nextStartedService)
        operation.detach()
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)
    private fun config(host: String) = AppConfig(ssh = SshConfig(host, username = "alice"))
    private fun host(activity: SettingsActivity) = field(activity, "hostEdit") as EditText
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun save(activity: SettingsActivity) = SettingsActivity::class.java.getDeclaredMethod("saveFromUi")
        .apply { isAccessible = true }.invoke(activity)
    private fun import(activity: SettingsActivity, uri: Uri) {
        SettingsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, 101, Activity.RESULT_OK, Intent().setData(uri))
    }
    private fun awaitResult(operation: Any) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (field(operation, "result") != null) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("Backup did not finish")
    }
}
