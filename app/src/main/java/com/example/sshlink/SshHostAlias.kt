package com.example.sshlink

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

object SshHostAlias {
    fun canonical(host: String, port: Int): String {
        require(port in 1..65535) { "SSH port must be 1..65535" }
        val normalized = canonicalHost(host)
        return if (normalized.contains(':')) "[$normalized]:$port" else "$normalized:$port"
    }

    /** Host text suitable for both DNS/socket connection and pin identity. */
    fun canonicalHost(host: String): String {
        var value = host.trim()
        require(value.isNotEmpty()) { "SSH host is required" }
        if (value.startsWith("[") && value.endsWith("]") && value.length > 2) {
            value = value.substring(1, value.length - 1)
        }
        value = value.trimEnd('.')
        require(value.isNotEmpty()) { "SSH host is required" }

        return if (value.contains(':')) {
            normalizeIpv6Literal(value)
        } else {
            normalizeDnsOrIpv4(value)
        }
    }

    fun canonicalAlias(alias: String): String {
        val text = alias.trim()
        require(text.isNotEmpty()) { "Host key alias is empty" }
        if (text.startsWith("[")) {
            val close = text.lastIndexOf(']')
            require(close > 1 && close + 2 <= text.length && text.getOrNull(close + 1) == ':') {
                "Invalid bracketed host key alias: $alias"
            }
            return canonical(text.substring(1, close), text.substring(close + 2).toInt())
        }
        val split = text.lastIndexOf(':')
        require(split > 0 && split < text.lastIndex) { "Invalid host key alias: $alias" }
        return canonical(text.substring(0, split), text.substring(split + 1).toInt())
    }

    private fun normalizeIpv6Literal(value: String): String {
        require('%' !in value) { "Scoped IPv6 addresses are not supported" }
        val address = runCatching { InetAddress.getByName(value) }.getOrNull()
        require(address is Inet6Address) { "Invalid IPv6 address: $value" }
        return formatIpv6Canonical(address.address)
    }

    /** RFC 5952-style lowercase IPv6 text derived from the 16 address bytes. */
    private fun formatIpv6Canonical(bytes: ByteArray): String {
        require(bytes.size == 16) { "IPv6 address must contain 16 bytes" }
        val groups = IntArray(8) { i ->
            ((bytes[i * 2].toInt() and 0xff) shl 8) or (bytes[i * 2 + 1].toInt() and 0xff)
        }

        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < groups.size) {
            if (groups[i] != 0) {
                i++
                continue
            }
            val start = i
            while (i < groups.size && groups[i] == 0) i++
            val length = i - start
            if (length >= 2 && length > bestLength) {
                bestStart = start
                bestLength = length
            }
        }

        val out = StringBuilder()
        i = 0
        while (i < groups.size) {
            if (i == bestStart) {
                out.append("::")
                i += bestLength
                if (i >= groups.size) break
                continue
            }
            if (out.isNotEmpty() && out.last() != ':') out.append(':')
            out.append(groups[i].toString(16))
            i++
        }
        return out.toString()
    }

    private fun normalizeDnsOrIpv4(value: String): String {
        // Reject ambiguous legacy numeric IPv4 forms such as 2130706433, 127.1,
        // and octets with leading zeroes. They may be interpreted differently by
        // resolver implementations and must not create alternate host-key identities.
        if (value.all { it.isDigit() || it == '.' }) {
            val parts = value.split('.')
            require(parts.size == 4) { "IPv4 address must use four dotted-decimal octets" }
            require(parts.all { part ->
                part.isNotEmpty() &&
                    (part == "0" || !part.startsWith('0')) &&
                    part.toIntOrNull() in 0..255
            }) { "Invalid or ambiguous IPv4 address: $value" }
            return parts.joinToString(".")
        }

        val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase()
        require(ascii.length <= 253) { "SSH host name is too long" }
        val labels = ascii.split('.')
        require(labels.all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first() != '-' &&
                label.last() != '-'
        }) { "Invalid SSH host name: $value" }
        return ascii
    }
}
