package com.example.sshlink

import com.jcraft.jsch.Session

/** Security-critical SSH session settings that must be applied to every connection. */
object SshSessionPolicy {
    const val STRICT_HOST_KEY_CHECKING = "yes"
    const val ENABLE_AUTH_NONE = "no"
    const val PREFERRED_AUTHENTICATIONS = "publickey"
    const val ENABLE_PUBKEY_AUTH_QUERY = "no"
    const val PUBKEY_ACCEPTED_ALGORITHMS = "ssh-ed25519"
    const val NUMBER_OF_PASSWORD_PROMPTS = "0"
    const val DISABLED_KEX = "diffie-hellman-group-exchange-sha256"

    fun apply(session: Session) {
        session.setConfig("StrictHostKeyChecking", STRICT_HOST_KEY_CHECKING)
        // JSch otherwise sends an initial SSH "none" authentication request and
        // accepts success from a server that permits it. This app requires actual
        // Ed25519 public-key authentication, so "none" login must be disabled.
        session.setConfig("enable_auth_none", ENABLE_AUTH_NONE)
        session.setConfig("PreferredAuthentications", PREFERRED_AUTHENTICATIONS)
        // Send a full signed Ed25519 authentication request instead of an
        // unsigned public-key capability query.
        session.setConfig("enable_pubkey_auth_query", ENABLE_PUBKEY_AUTH_QUERY)
        session.setConfig("PubkeyAcceptedAlgorithms", PUBKEY_ACCEPTED_ALGORITHMS)
        session.setConfig("NumberOfPasswordPrompts", NUMBER_OF_PASSWORD_PROMPTS)

        // Preserve JSch's current KEX preference order and future defaults, but
        // remove the single DH group-exchange method that this app deliberately
        // does not allow. Avoid maintaining a separate cipher/KEX allow-list.
        session.setConfig("kex", hardenedKex(session.getConfig("kex")))
    }

    internal fun hardenedKex(current: String): String {
        val result = current
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it == DISABLED_KEX }
            .joinToString(",")
        require(result.isNotEmpty()) { "No SSH key-exchange algorithms remain after policy filtering" }
        return result
    }
}
