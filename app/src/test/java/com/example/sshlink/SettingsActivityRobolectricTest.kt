package com.example.sshlink

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActivityRobolectricTest {
    @Test fun slowImportDoesNotBlockUiAndSurvivesRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.setTunnelDesired(false)
        settings.save(AppConfig(ssh = SshConfig("old.example.com", username = "alice")))
        val imported = AppConfig(ssh = SshConfig("new.example.com", username = "alice"))
        val uri = Uri.parse("content://sshlink-test/slow-import.json")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readOnMain = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        shadowOf(context.contentResolver).registerInputStream(uri,
            object : ByteArrayInputStream(imported.toJson().toString().toByteArray()) {
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    readOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return super.read(bytes, off, len)
                }
                override fun close() { closed.set(true); super.close() }
            })
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val original = controller.get()
            deliverDocument(original, 101, uri)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(readOnMain.get())
            assertFalse(editText(original, "hostEdit").isEnabled)
            var mainResponded = false
            Handler(Looper.getMainLooper()).post { mainResponded = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(mainResponded)
            controller.recreate()
            val recreated = controller.get()
            assertNotSame(original, recreated)
            assertTrue(original.isDestroyed)
            assertFalse(editText(recreated, "hostEdit").isEnabled)
            release.countDown()
            awaitBackup(recreated)
            assertEquals(imported, settings.load())
            assertEquals("new.example.com", editText(recreated, "hostEdit").text.toString())
            assertTrue(editText(recreated, "hostEdit").isEnabled)
            assertTrue(closed.get())
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun exportWritesAndClosesDocumentOffMainThread() {
        val context = RuntimeEnvironment.getApplication()
        val config = AppConfig(ssh = SshConfig("export.example.com", username = "alice"))
        SettingsRepository(context).save(config)
        val uri = Uri.parse("content://sshlink-test/export.json")
        val writeOnMain = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                writeOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                super.write(bytes, off, len)
            }
            override fun close() { closed.set(true); super.close() }
        }
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            deliverDocument(controller.get(), 100, uri)
            awaitBackup(controller.get())
            assertFalse(writeOnMain.get())
            assertTrue(closed.get())
            assertEquals(config, AppConfig.fromJson(org.json.JSONObject(output.toString("UTF-8"))))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun failedImportKeepsStoredSettingsAndRestoresControls() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        val config = AppConfig(ssh = SshConfig("original.example.com", username = "alice"))
        settings.save(config)
        val uri = Uri.parse("content://sshlink-test/unavailable.json")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int = throw IOException("Provider unavailable")
        })
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            deliverDocument(controller.get(), 101, uri)
            awaitBackup(controller.get())
            assertEquals(config, settings.load())
            assertTrue(editText(controller.get(), "hostEdit").isEnabled)
            assertEquals("Backup error", shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun completionAfterActivityFinishesDoesNotShowDialog() {
        val context = RuntimeEnvironment.getApplication()
        SettingsRepository(context).setTunnelDesired(false)
        val release = CountDownLatch(1)
        val uri = Uri.parse("content://sshlink-test/delayed-failure.json")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                check(release.await(5, TimeUnit.SECONDS))
                throw IOException("Delayed provider error")
            }
        })
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        deliverDocument(controller.get(), 101, uri)
        val operation = requireNotNull(field(controller.get(), "backupOperation"))
        controller.get().finish()
        controller.pause().stop().destroy()
        release.countDown()
        awaitCondition { field(operation, "result") != null }
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    @Test fun importKeepsValidPrivateKeyWithoutRegenerationWarning() {
        checkImportKeyWarning("valid", false)
    }

    @Test fun importWarnsWhenPrivateKeyIsMissing() {
        checkImportKeyWarning("missing", true)
    }

    @Test fun importWarnsWhenPrivateKeyIsInvalid() {
        checkImportKeyWarning("invalid", true)
    }

    private fun checkImportKeyWarning(keyState: String, expectWarning: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        settings.setTunnelDesired(false)
        val keys = OpenSshEd25519KeyManager(context)
        when (keyState) {
            "valid" -> keys.generate()
            "missing" -> keys.privateKeyFile.delete()
            "invalid" -> {
                keys.privateKeyFile.parentFile!!.mkdirs()
                keys.privateKeyFile.writeText("invalid key")
            }
        }
        val originalKey = keys.privateKeyFile.takeIf { it.exists() }?.readText()
        val config = AppConfig(ssh = SshConfig("ssh.example.com", username = "alice"))
        settings.save(config)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val uri = Uri.parse("content://sshlink-test/backup.json")
            shadowOf(activity.contentResolver).registerInputStream(
                uri, config.toJson().toString().byteInputStream(),
            )
            SettingsActivity::class.java.getDeclaredMethod(
                "onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java,
            ).apply { isAccessible = true }
                .invoke(activity, 101, Activity.RESULT_OK, Intent().setData(uri))
            awaitBackup(activity)

            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            if (expectWarning) {
                assertNotNull(dialog)
                assertEquals("SSH key regeneration required", shadowOf(dialog).title.toString())
            } else {
                assertNull(dialog)
            }
            assertEquals(config, settings.load())
            assertEquals(originalKey, keys.privateKeyFile.takeIf { it.exists() }?.readText())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun configurationChangePreservesUnsavedFieldsAndForwards() {
        val originalController = Robolectric.buildActivity(SettingsActivity::class.java)
            .create()
            .start()
            .resume()
        val original = originalController.get()
        editText(original, "hostEdit").setText("unsaved.example.com")
        SettingsActivity::class.java
            .getDeclaredMethod(
                "addForwardRow",
                String::class.java,
                String::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
            .invoke(original, "Office PC", "13389", "192.168.1.1:3389")

        val state = Bundle()
        originalController.saveInstanceState(state).pause().stop().destroy()

        val recreatedController = Robolectric.buildActivity(SettingsActivity::class.java)
            .create(state)
            .start()
            .resume()
        val recreated = recreatedController.get()

        assertEquals("unsaved.example.com", editText(recreated, "hostEdit").text.toString())
        val rows = field(recreated, "forwardRows") as List<*>
        assertEquals(1, rows.size)
        val row = requireNotNull(rows.single())
        assertEquals("Office PC", editText(row, "name").text.toString())
        assertEquals("13389", editText(row, "local").text.toString())
        assertEquals("192.168.1.1:3389", editText(row, "destination").text.toString())
        recreatedController.pause().stop().destroy()
    }

    private fun editText(instance: Any, name: String): EditText = field(instance, name) as EditText

    private fun deliverDocument(activity: SettingsActivity, request: Int, uri: Uri) {
        SettingsActivity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java,
        ).apply { isAccessible = true }
            .invoke(activity, request, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun awaitBackup(activity: SettingsActivity) = awaitCondition { field(activity, "backupOperation") == null }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail("Backup did not finish")
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .get(instance)
}
