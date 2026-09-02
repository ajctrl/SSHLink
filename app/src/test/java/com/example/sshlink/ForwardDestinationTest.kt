package com.example.sshlink

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardDestinationTest {
    @Test fun parsesIpv4Destination() {
        assertEquals(
            ForwardDestination("192.168.1.1", 3389),
            ForwardDestination.parse("192.168.1.1:3389"),
        )
    }

    @Test fun parsesAndFormatsIpv6Destination() {
        val destination = ForwardDestination.parse("[2001:db8::1]:443")

        assertEquals(ForwardDestination("2001:db8::1", 443), destination)
        assertEquals("[2001:db8::1]:443", destination.displayText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDestinationWithoutPort() {
        ForwardDestination.parse("192.168.1.1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangePort() {
        ForwardDestination.parse("192.168.1.1:65536")
    }
}
