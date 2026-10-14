package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

class ConfigStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missingConfigReturnsDefault() {
        val file = temp.newFile("config.json").apply { delete() }
        assertEquals(AppConfig(), ConfigStore(file).load())
    }

    @Test fun malformedFieldTypesCannotOverwriteExistingSettings() {
        val file = temp.root.resolve("config.json")
        val store = ConfigStore(file)
        val config = AppConfig(
            ssh = SshConfig("ssh.example.com", username = "alice"),
            forwards = listOf(ForwardConfig(8080, "service.lan", 80)),
        )
        store.save(config)
        val before = file.readText()
        val malformed = listOf(
            config.toJson().put("forwards", JSONObject()),
            config.toJson().put("forwards", JSONObject.NULL),
            config.toJson().put("ssh", "invalid"),
            config.toJson().put("app", false),
            config.toJson().put("schemaVersion", "1"),
            config.toJson().apply { getJSONObject("ssh").put("port", "22") },
            config.toJson().apply { getJSONObject("ssh").put("username", JSONObject.NULL) },
            config.toJson().apply { getJSONArray("forwards").getJSONObject(0).put("localPort", 8080.5) },
            config.toJson().apply { getJSONArray("forwards").getJSONObject(0).put("remotePort", 4_294_967_376L) },
            config.toJson().apply { getJSONArray("forwards").getJSONObject(0).put("remoteHost", 123) },
        )
        malformed.forEach { json ->
            assertThrows(ConfigStore.ConfigCorruptedException::class.java) {
                store.importJson(json.toString())
            }
            assertEquals(before, file.readText())
        }
    }

    @Test fun wrongTypeOnDiskBlocksLoadSaveAndExport() {
        val file = temp.newFile("wrong-type.json")
        val config = AppConfig(ssh = SshConfig("ssh.example.com", username = "alice"))
        file.writeText(config.toJson().put("forwards", JSONObject()).toString())
        val before = file.readText()
        val store = ConfigStore(file)
        assertThrows(ConfigStore.ConfigCorruptedException::class.java) { store.load() }
        assertThrows(ConfigStore.ConfigCorruptedException::class.java) { store.exportJson() }
        assertThrows(ConfigStore.ConfigCorruptedException::class.java) { store.save(config) }
        assertEquals(before, file.readText())
    }

    @Test fun absentOptionalFieldsStillUseDefaults() {
        val store = ConfigStore(temp.root.resolve("minimal.json"))
        val imported = store.importJson("""{"ssh":{"host":"ssh.example.com","username":"alice"}}""")
        assertEquals(AppConfig(ssh = SshConfig("ssh.example.com", username = "alice")), imported)
    }

    @Test fun pendingImportCannotOverwriteSaveFromAnotherStoreInstance() {
        val file = temp.root.resolve("shared.json")
        val importing = ConfigStore(file)
        val saving = ConfigStore(file)
        val original = AppConfig(ssh = SshConfig("original.example.com", username = "alice"))
        val later = original.copy(ssh = original.ssh.copy(host = "later.example.com"))
        importing.save(original)
        val revision = importing.revision()
        saving.save(later)

        assertThrows(ConfigStore.ConcurrentUpdateException::class.java) {
            importing.importJson(original.toJson().toString(), revision)
        }
        assertEquals(later, saving.load())
    }

    @Test fun evenSavingTheSameContentsInvalidatesAnOlderImport() {
        val store = ConfigStore(temp.root.resolve("same.json"))
        val original = AppConfig(ssh = SshConfig("original.example.com", username = "alice"))
        store.save(original)
        val revision = store.revision()
        store.save(original)
        val imported = original.copy(ssh = original.ssh.copy(host = "imported.example.com"))
        assertThrows(ConfigStore.ConcurrentUpdateException::class.java) {
            store.importJson(imported.toJson().toString(), revision)
        }
        assertEquals(original, store.load())
    }

    @Test fun firstCommittedImportInvalidatesOtherPendingImports() {
        val file = temp.root.resolve("imports.json")
        val first = ConfigStore(file)
        val second = ConfigStore(file)
        val firstRevision = first.revision()
        val secondRevision = second.revision()
        val newer = AppConfig(ssh = SshConfig("newer.example.com", username = "alice"))
        second.importJson(newer.toJson().toString(), secondRevision)
        assertThrows(ConfigStore.ConcurrentUpdateException::class.java) {
            first.importJson(AppConfig().toJson().toString(), firstRevision)
        }
        assertEquals(newer, first.load())
    }

    @Test fun failedWritesAndWritesToOtherFilesDoNotInvalidatePendingImport() {
        val store = ConfigStore(temp.root.resolve("pending.json"))
        val revision = store.revision()
        assertThrows(ConfigStore.ConfigCorruptedException::class.java) { store.importJson("broken") }
        ConfigStore(temp.root.resolve("unrelated.json")).save(AppConfig())
        val imported = AppConfig(ssh = SshConfig("imported.example.com", username = "alice"))
        assertEquals(imported, store.importJson(imported.toJson().toString(), revision))
    }

    @Test fun conditionalImportStillRecoversACorruptFile() {
        val file = temp.newFile("corrupt-import.json").apply { writeText("broken") }
        val store = ConfigStore(file)
        val revision = store.revision()
        val imported = AppConfig(ssh = SshConfig("recovered.example.com", username = "alice"))
        assertEquals(imported, store.importJson(imported.toJson().toString(), revision))
        assertEquals(imported, store.load())
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
