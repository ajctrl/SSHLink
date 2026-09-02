package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missingConfigReturnsDefault() {
        val file = temp.newFile("config.json").apply { delete() }
        assertEquals(AppConfig(), ConfigStore(file).load())
    }

    @Test fun corruptConfigFailsClosedAndNormalSaveDoesNotOverwriteIt() {
        val file = temp.newFile("config.json")
        file.writeText("{ definitely-not-json")
        val before = file.readText()
        val store = ConfigStore(file)

        var loadFailed = false
        try { store.load() } catch (_: ConfigStore.ConfigCorruptedException) { loadFailed = true }
        assertTrue(loadFailed)

        var saveFailed = false
        try {
            store.save(AppConfig(ssh = SshConfig("ssh.example.com", username = "alice")))
        } catch (_: ConfigStore.ConfigCorruptedException) {
            saveFailed = true
        }
        assertTrue(saveFailed)
        assertEquals(before, file.readText())
    }

    @Test fun semanticValidationErrorCanBeLoadedAndFixed() {
        val file = temp.newFile("config.json")
        val legacy = AppConfig(
            ssh = SshConfig("ssh.example.com", username = "alice"),
            forwards = listOf(ForwardConfig(443, "service.lan", 443)),
        )
        file.writeText(legacy.toJson().toString())
        val store = ConfigStore(file)
        val loaded = store.load()
        assertTrue(loaded.validationErrors().any { it.contains("1024..65535") })

        val fixed = loaded.copy(forwards = listOf(ForwardConfig(8443, "service.lan", 443)))
        store.save(fixed)
        assertEquals(fixed, store.load())
    }

    @Test fun validatedImportIsExplicitRecoveryPath() {
        val file = temp.newFile("config.json")
        file.writeText("broken")
        val store = ConfigStore(file)
        val expected = AppConfig(
            ssh = SshConfig("ssh.example.com", 22, "alice"),
            forwards = listOf(ForwardConfig(8080, "service.lan", 80)),
        )
        val imported = store.importJson(expected.toJson().toString())
        assertEquals(expected, imported)
        assertEquals(expected, store.load())
    }

    @Test fun exportOfCorruptConfigFailsInsteadOfExportingDefaults() {
        val file = temp.newFile("config.json")
        file.writeText("broken")
        var failed = false
        try { ConfigStore(file).exportJson() } catch (_: ConfigStore.ConfigCorruptedException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun oversizedImportIsRejectedBeforeParsing() {
        val file = temp.root.resolve("oversized.json")
        val store = ConfigStore(file)
        val oversized = "x".repeat(ConfigLimits.MAX_IMPORT_BYTES + 1)
        try {
            store.importJson(oversized)
            fail("Expected oversized import to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Import exceeds"))
        }
        assertFalse(file.exists())
    }
}
