package com.example.sshlink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsBackupLifecycleTest {
    private fun config(host: String) = AppConfig(ssh = SshConfig(host, username = "alice"))
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    private fun host(activity: SettingsActivity) = field(activity, "hostEdit") as EditText
    private fun call(activity: SettingsActivity, name: String) = SettingsActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun startImport(activity: SettingsActivity, uri: Uri) {
        SettingsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, 101, Activity.RESULT_OK, Intent().setData(uri))
    }
    private fun awaitResult(operation: Any) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (field(operation, "result") != null) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("backup did not complete")
    }
    private fun slowInput(uri: Uri, entered: CountDownLatch, release: CountDownLatch) {
        val bytes = config("imported.example.com").toJson().toString().toByteArray()
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri,
            object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    entered.countDown()
                    // Model a provider that does not abort its read on interruption.
                    while (true) {
                        try {
                            check(release.await(5, TimeUnit.SECONDS))
                            break
                        } catch (_: InterruptedException) { }
                    }
                    return super.read(buffer, off, len)
                }
            })
    }
    @Test fun finishedImportCannotOverwriteLaterSaveFromNewActivity() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.setTunnelDesired(false)
        settings.save(config("original.example.com"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://review/slow.json")
        slowInput(uri, entered, release)
        val first = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        startImport(first.get(), uri)
        val operation = requireNotNull(field(first.get(), "backupOperation"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        first.get().finish()
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            host(second.get()).setText("later-save.example.com")
            call(second.get(), "saveFromUi")
            assertEquals("later-save.example.com", settings.load().ssh.host)
            release.countDown()
            awaitResult(operation)
            assertEquals("later-save.example.com", settings.load().ssh.host)
            assertEquals("later-save.example.com", host(second.get()).text.toString())
            assertNull(shadowOf(context).nextStartedService)
        } finally {
            release.countDown()
            second.pause().stop().destroy()
        }
    }
    @Test fun processRecreationLoadsCompletedImportInsteadOfOldDraft() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.setTunnelDesired(false)
        settings.save(config("original.example.com"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://review/stopped.json")
        slowInput(uri, entered, release)
        val first = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        startImport(first.get(), uri)
        val operation = requireNotNull(field(first.get(), "backupOperation"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop()
        release.countDown()
        awaitResult(operation)
        assertEquals("imported.example.com", settings.load().ssh.host)
        first.destroy()
        // A process recreation receives saved state, but no retained worker.
        val restored = Robolectric.buildActivity(SettingsActivity::class.java).create(saved).start().resume()
        try {
            assertEquals("imported.example.com", host(restored.get()).text.toString())
            call(restored.get(), "saveFromUi")
            assertEquals("imported.example.com", settings.load().ssh.host)
        } finally { restored.pause().stop().destroy() }
    }

    @Test fun processRecreationAfterInterruptedImportLoadsStorageAndEnablesEditing() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.setTunnelDesired(false)
        settings.save(config("original.example.com"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://review/interrupted.json")
        slowInput(uri, entered, release)
        val first = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        host(first.get()).setText("unsaved-draft.example.com")
        startImport(first.get(), uri)
        val operation = requireNotNull(field(first.get(), "backupOperation"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        release.countDown()
        awaitResult(operation)
        val restored = Robolectric.buildActivity(SettingsActivity::class.java).create(saved).start().resume()
        try {
            assertEquals("original.example.com", settings.load().ssh.host)
            assertEquals("original.example.com", host(restored.get()).text.toString())
            assertTrue(host(restored.get()).isEnabled)
            assertNull(field(restored.get(), "backupOperation"))
        } finally { restored.pause().stop().destroy() }
    }

    @Test fun processRecreationDuringExportPreservesUnsavedDraft() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.save(config("original.example.com"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://review/export.json")
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, off: Int, len: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                super.write(buffer, off, len)
            }
        }
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        val first = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        host(first.get()).setText("unsaved-draft.example.com")
        SettingsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(first.get(), 100, Activity.RESULT_OK, Intent().setData(uri))
        val operation = requireNotNull(field(first.get(), "backupOperation"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val saved = Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        release.countDown()
        awaitResult(operation)
        val restored = Robolectric.buildActivity(SettingsActivity::class.java).create(saved).start().resume()
        try {
            assertEquals("unsaved-draft.example.com", host(restored.get()).text.toString())
            assertTrue(host(restored.get()).isEnabled)
            assertEquals("original.example.com", settings.load().ssh.host)
            assertEquals(config("original.example.com"), AppConfig.fromJson(org.json.JSONObject(output.toString("UTF-8"))))
        } finally { restored.pause().stop().destroy() }
    }
}
