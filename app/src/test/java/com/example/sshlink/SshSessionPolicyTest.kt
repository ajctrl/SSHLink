package com.example.sshlink

import com.jcraft.jsch.JSch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSessionPolicyTest {
    @Test
    fun authenticationIsPublicKeyOnlyAndNoneAuthIsDisabled() {
        val session = JSch().getSession("user", "example.com", 22)
        SshSessionPolicy.apply(session)

        assertEquals("yes", session.getConfig("StrictHostKeyChecking"))
        assertEquals("no", session.getConfig("enable_auth_none"))
        assertEquals("publickey", session.getConfig("PreferredAuthentications"))
        assertEquals("no", session.getConfig("enable_pubkey_auth_query"))
        assertEquals("ssh-ed25519", session.getConfig("PubkeyAcceptedAlgorithms"))
        assertEquals("0", session.getConfig("NumberOfPasswordPrompts"))
        assertFalse(session.getConfig("kex").split(',').contains(SshSessionPolicy.DISABLED_KEX))
    }

    @Test
    fun kexPolicyRemovesOnlyGroupExchangeAndPreservesOrder() {
        val session = JSch().getSession("user", "example.com", 22)
        session.setConfig(
            "kex",
            "curve25519-sha256,${SshSessionPolicy.DISABLED_KEX},diffie-hellman-group14-sha256",
        )

        SshSessionPolicy.apply(session)

        assertEquals(
            "curve25519-sha256,diffie-hellman-group14-sha256",
            session.getConfig("kex"),
        )
        assertTrue(session.getConfig("kex").startsWith("curve25519-sha256"))
    }
}
