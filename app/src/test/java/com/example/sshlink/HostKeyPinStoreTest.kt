package com.example.sshlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class HostKeyPinStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun inspectDoesNotPersistUntilExplicitCommit() {
        val file = temp.newFile("pins.json").apply { delete() }
        val store = HostKeyPinStore(file)
        val key = byteArrayOf(9, 8, 7)
        assertEquals(HostKeyPinStore.CheckResult.MISSING, store.inspect("ssh.example.com:22", key))
        assertFalse(file.exists())
        assertEquals(HostKeyPinStore.CheckResult.PINNED, store.commit("ssh.example.com:22", key))
        assertTrue(file.exists())
        assertEquals(HostKeyPinStore.CheckResult.MATCH, store.inspect("ssh.example.com:22", key))
    }

    @Test fun tofuThenMatchAndChange() {
        val store = HostKeyPinStore(temp.newFile("pins.json").apply { delete() })
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertEquals(HostKeyPinStore.CheckResult.PINNED, store.check("SSH.Example.COM.:22", a))
        assertEquals(HostKeyPinStore.CheckResult.MATCH, store.check("ssh.example.com:22", a))
        assertEquals(HostKeyPinStore.CheckResult.CHANGED, store.check("ssh.example.com:22", b))
    }

    @Test fun legacyAliasIsMigrated() {
        val file = temp.newFile("pins.json")
        val key = byteArrayOf(7, 8, 9)
        file.writeText(JSONArray().put(JSONObject().apply {
            put("host", "SSH.Example.COM.:22")
            put("key", Base64.getEncoder().encodeToString(key))
        }).toString())
        val entries = HostKeyPinStore(file).entries()
        assertEquals(setOf("ssh.example.com:22"), entries.keys)
        assertTrue(file.readText().contains("ssh.example.com:22"))
    }

    @Test fun legacyIpv6SpellingMigratesToCanonicalIdentity() {
        val file = temp.newFile("pins.json")
        val key = byteArrayOf(3, 4, 5)
        file.writeText(JSONArray().put(JSONObject().apply {
            put("host", "[2001:0DB8:0:0:0:0:0:1]:22")
            put("key", Base64.getEncoder().encodeToString(key))
        }).toString())
        val entries = HostKeyPinStore(file).entries()
        assertEquals(setOf("[2001:db8::1]:22"), entries.keys)
        assertTrue(file.readText().contains("[2001:db8::1]:22"))
    }

    @Test fun conflictingNormalizedAliasesFailClosed() {
        val file = temp.newFile("pins.json")
        file.writeText(JSONArray()
            .put(JSONObject().apply {
                put("host", "SSH.Example.COM.:22")
                put("key", Base64.getEncoder().encodeToString(byteArrayOf(1)))
            })
            .put(JSONObject().apply {
                put("host", "ssh.example.com:22")
                put("key", Base64.getEncoder().encodeToString(byteArrayOf(2)))
            }).toString())
        var failed = false
        try { HostKeyPinStore(file).entries() } catch (_: HostKeyPinStore.PinStoreException) { failed = true }
        assertTrue(failed)
    }

    @Test fun forgetUsesCanonicalAlias() {
        val store = HostKeyPinStore(temp.newFile("pins.json").apply { delete() })
        store.check("SSH.Example.COM.:22", byteArrayOf(1, 2))
        assertTrue(store.forget("ssh.example.com:22"))
        assertFalse(store.forget("ssh.example.com:22"))
    }
    @Test fun corruptStoreCanBeResetWithoutDeletingOriginalBytes() {
        val file = temp.newFile("pins.json")
        file.writeText("not-json")
        val store = HostKeyPinStore(file)
        var failed = false
        try { store.entries() } catch (_: HostKeyPinStore.PinStoreException) { failed = true }
        assertTrue(failed)

        val backup = store.resetAllPreservingBackup()
        assertFalse(file.exists())
        assertTrue(backup != null && backup.exists())
        assertEquals("not-json", backup!!.readText())
        assertTrue(store.entries().isEmpty())
    }

}
