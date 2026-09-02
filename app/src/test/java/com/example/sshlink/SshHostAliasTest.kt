package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SshHostAliasTest {
    @Test
    fun connectionHostUsesTheSameNormalizationAsPinIdentity() {
        assertEquals("xn--bcher-kva.example", SshHostAlias.canonicalHost("  BÜCHER.example.  "))
        assertEquals("2001:db8::1", SshHostAlias.canonicalHost("[2001:0DB8:0:0:0:0:0:1]"))
        assertEquals(
            "xn--bcher-kva.example:22",
            SshHostAlias.canonical("  BÜCHER.example.  ", 22),
        )
    }

    @Test fun dnsNameIsCanonicalized() {
        assertEquals("ssh.example.com:22", SshHostAlias.canonical(" SSH.Example.COM. ", 22))
    }

    @Test fun idnIsAscii() {
        assertEquals("xn--r8jz45g.xn--zckzah:22", SshHostAlias.canonical("例え.テスト", 22))
    }

    @Test fun equivalentIpv6SpellingsShareOneCanonicalPinIdentity() {
        val compact = SshHostAlias.canonical("2001:DB8::1", 2222)
        val expanded = SshHostAlias.canonical("2001:0db8:0:0:0:0:0:1", 2222)
        assertEquals("[2001:db8::1]:2222", compact)
        assertEquals(compact, expanded)
        assertEquals(compact, SshHostAlias.canonicalAlias("2001:0DB8:0:0:0:0:0:1:2222"))
    }

    @Test fun ipv6CompressionUsesLongestRunAndFirstRunOnTie() {
        assertEquals(
            "[2001::1:0:0:1:1]:22",
            SshHostAlias.canonical("2001:0:0:1:0:0:1:1", 22),
        )
    }

    @Test fun colonDoesNotAutomaticallyMakeAValidIpv6Address() {
        assertThrows(IllegalArgumentException::class.java) {
            SshHostAlias.canonical("not:ipv6", 22)
        }
    }

    @Test fun scopedIpv6IsRejectedToAvoidInterfaceDependentAliases() {
        assertThrows(IllegalArgumentException::class.java) {
            SshHostAlias.canonical("fe80::1%wlan0", 22)
        }
    }

    @Test fun invalidDottedIpv4IsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SshHostAlias.canonical("999.1.2.3", 22)
        }
    }

    @Test fun legacyNumericIpv4SpellingsAreRejected() {
        listOf("2130706433", "127.1", "127.0.0.01", "01.2.3.4").forEach { host ->
            assertThrows(host, IllegalArgumentException::class.java) {
                SshHostAlias.canonical(host, 22)
            }
        }
    }
}
