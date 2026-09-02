package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {
    @Test fun jsonRoundTrip() {
        val config = AppConfig(
            ssh = SshConfig("ssh.example.com", 2222, "alice", keepAliveIntervalSec = 15, keepAliveCountMax = 4),
            forwards = listOf(
                ForwardConfig(33293, "192.168.1.50", 33293, name = "Desktop"),
                ForwardConfig(8443, "service.lan", 443),
            ),
            app = AppSettings(1000),
        )
        assertEquals(config, AppConfig.fromJson(config.toJson()))
    }

    @Test fun forwardNameIsOptionalForExistingJson() {
        val config = AppConfig(
            ssh = SshConfig("ssh.example.com", username = "alice"),
            forwards = listOf(ForwardConfig(13389, "192.168.1.1", 3389)),
        )
        val json = config.toJson()
        json.getJSONArray("forwards").getJSONObject(0).remove("name")

        assertEquals("", AppConfig.fromJson(json).forwards.single().name)
    }

    @Test fun privilegedLocalPortIsRejectedOnAndroid() {
        val config = AppConfig(
            ssh = SshConfig("ssh.example.com", username = "alice"),
            forwards = listOf(ForwardConfig(443, "service.lan", 443)),
        )
        assertTrue(config.validationErrors().any { it.contains("1024..65535") })
    }

    @Test fun duplicateLocalPortsAreRejected() {
        val config = AppConfig(
            ssh = SshConfig("ssh.example.com", username = "alice"),
            forwards = listOf(
                ForwardConfig(8080, "a", 80),
                ForwardConfig(8080, "b", 80),
            ),
        )
        assertTrue(config.validationErrors().any { it.contains("duplicated") })
    }
    @Test fun invalidIpv6LikeSshHostIsRejectedDuringValidation() {
        val config = AppConfig(ssh = SshConfig("not:ipv6", username = "alice"))
        assertTrue(config.validationErrors().any { it.contains("SSH Host is invalid") })
    }


    @Test
    fun rejectsExcessiveForwardsAndOversizedFields() {
        val forwards = (1..(ConfigLimits.MAX_FORWARDS + 1)).map { index ->
            ForwardConfig(1024 + index, "host", 22)
        }
        val config = AppConfig(
            ssh = SshConfig(
                host = "h".repeat(ConfigLimits.MAX_SSH_HOST_CHARS + 1),
                username = "u".repeat(ConfigLimits.MAX_USERNAME_CHARS + 1),
            ),
            forwards = forwards,
        )
        val errors = config.validationErrors()
        assertTrue(errors.any { it.contains("Too many forwards") })
        assertTrue(errors.any { it.contains("SSH Host is too long") })
        assertTrue(errors.any { it.contains("Username is too long") })
    }

    @Test fun oversizedForwardNameIsRejected() {
        val config = AppConfig(
            ssh = SshConfig("ssh.example.com", username = "alice"),
            forwards = listOf(
                ForwardConfig(
                    13389,
                    "192.168.1.1",
                    3389,
                    name = "n".repeat(ConfigLimits.MAX_FORWARD_NAME_CHARS + 1),
                )
            ),
        )

        assertTrue(config.validationErrors().any { it.contains("Name is too long") })
    }
}
