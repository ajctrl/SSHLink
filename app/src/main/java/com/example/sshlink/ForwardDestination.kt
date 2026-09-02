package com.example.sshlink

data class ForwardDestination(
    val host: String,
    val port: Int,
) {
    fun displayText(): String {
        val displayHost = when {
            host.startsWith("[") && host.endsWith("]") -> host
            ':' in host -> "[$host]"
            else -> host
        }
        return "$displayHost:$port"
    }

    companion object {
        fun parse(value: String): ForwardDestination {
            val text = value.trim()
            require(text.isNotEmpty()) { "Destination is required (example: 192.168.1.1:3389)" }

            val (host, portText) = if (text.startsWith("[")) {
                val closingBracket = text.indexOf(']')
                require(closingBracket > 1 && text.getOrNull(closingBracket + 1) == ':') {
                    "Destination must be host:port; use [address]:port for IPv6"
                }
                text.substring(1, closingBracket).trim() to
                    text.substring(closingBracket + 2).trim()
            } else {
                val separator = text.lastIndexOf(':')
                require(separator in 1 until text.lastIndex) {
                    "Destination must be host:port (example: 192.168.1.1:3389)"
                }
                val parsedHost = text.substring(0, separator).trim()
                require(':' !in parsedHost) { "IPv6 destinations must use [address]:port" }
                parsedHost to text.substring(separator + 1).trim()
            }

            require(host.isNotEmpty()) { "Destination host is required" }
            val port = portText.toIntOrNull()
            require(port != null && port in 1..65535) { "Destination port must be 1..65535" }
            return ForwardDestination(host, port)
        }
    }
}
