package com.example.sshlink

import org.json.JSONArray
import org.json.JSONObject

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val keyId: String = "default",
    val keepAliveIntervalSec: Int = 30,
    val keepAliveCountMax: Int = 3,
)

data class ForwardConfig(
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val name: String = "",
)

data class AppSettings(
    val logLimit: Int = 500,
)

data class AppConfig(
    val schemaVersion: Int = 1,
    val ssh: SshConfig = SshConfig(),
    val forwards: List<ForwardConfig> = emptyList(),
    val app: AppSettings = AppSettings(),
) {
    fun validationErrors(): List<String> {
        val errors = mutableListOf<String>()
        if (ssh.host.isBlank()) {
            errors += "SSH Host is required"
        } else if (ssh.host.length > ConfigLimits.MAX_SSH_HOST_CHARS) {
            errors += "SSH Host is too long (max ${ConfigLimits.MAX_SSH_HOST_CHARS} characters)"
        }
        if (ssh.port !in 1..65535) {
            errors += "SSH Port must be 1..65535"
        } else if (ssh.host.isNotBlank()) {
            runCatching { SshHostAlias.canonical(ssh.host, ssh.port) }
                .exceptionOrNull()
                ?.let { errors += "SSH Host is invalid: ${it.message ?: "invalid host"}" }
        }
        if (ssh.username.isBlank()) errors += "Username is required"
        if (ssh.username.length > ConfigLimits.MAX_USERNAME_CHARS) errors += "Username is too long (max ${ConfigLimits.MAX_USERNAME_CHARS} characters)"
        if (ssh.keyId != "default") errors += "Unsupported key id: ${ssh.keyId}"
        if (ssh.keepAliveIntervalSec !in 0..3600) errors += "ServerAliveInterval must be 0..3600 seconds"
        if (ssh.keepAliveCountMax !in 1..100) errors += "ServerAliveCountMax must be 1..100"

        if (forwards.size > ConfigLimits.MAX_FORWARDS) errors += "Too many forwards (max ${ConfigLimits.MAX_FORWARDS})"
        val localPorts = mutableSetOf<Int>()
        forwards.forEachIndexed { index, f ->
            val n = index + 1
            if (f.localPort !in 1024..65535) errors += "Forward $n: Local Port must be 1024..65535 on Android"
            if (!localPorts.add(f.localPort)) errors += "Forward $n: Local Port ${f.localPort} is duplicated"
            if (f.name.length > ConfigLimits.MAX_FORWARD_NAME_CHARS) errors += "Forward $n: Name is too long (max ${ConfigLimits.MAX_FORWARD_NAME_CHARS} characters)"
            if (f.name.any(Char::isISOControl)) errors += "Forward $n: Name must not contain control characters"
            if (f.remoteHost.isBlank()) errors += "Forward $n: Destination host is required"
            if (f.remoteHost.length > ConfigLimits.MAX_REMOTE_HOST_CHARS) errors += "Forward $n: Destination host is too long (max ${ConfigLimits.MAX_REMOTE_HOST_CHARS} characters)"
            if (f.remotePort !in 1..65535) errors += "Forward $n: Destination port must be 1..65535"
        }
        if (app.logLimit !in 100..5000) errors += "Log limit must be 100..5000"
        return errors
    }

    fun hasMeaningfulSettings(): Boolean =
        ssh.host.isNotBlank() || ssh.username.isNotBlank() || forwards.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("ssh", JSONObject().apply {
            put("host", ssh.host)
            put("port", ssh.port)
            put("username", ssh.username)
            put("keyId", ssh.keyId)
            put("keepAliveIntervalSec", ssh.keepAliveIntervalSec)
            put("keepAliveCountMax", ssh.keepAliveCountMax)
        })
        put("forwards", JSONArray().apply {
            forwards.forEach { f ->
                put(JSONObject().apply {
                    put("name", f.name)
                    put("localPort", f.localPort)
                    put("remoteHost", f.remoteHost)
                    put("remotePort", f.remotePort)
                })
            }
        })
        put("app", JSONObject().apply {
            put("logLimit", app.logLimit)
        })
    }

    companion object {
        fun fromJson(obj: JSONObject): AppConfig {
            val schema = obj.optInt("schemaVersion", 1)
            require(schema == 1) { "Unsupported schemaVersion: $schema" }

            val sshObj = obj.optJSONObject("ssh") ?: JSONObject()
            val ssh = SshConfig(
                host = sshObj.optString("host", ""),
                port = sshObj.optInt("port", 22),
                username = sshObj.optString("username", ""),
                keyId = sshObj.optString("keyId", "default"),
                keepAliveIntervalSec = sshObj.optInt("keepAliveIntervalSec", 30),
                keepAliveCountMax = sshObj.optInt("keepAliveCountMax", 3),
            )

            val forwardsArray = obj.optJSONArray("forwards") ?: JSONArray()
            require(forwardsArray.length() <= ConfigLimits.MAX_FORWARDS) {
                "Too many forwards (max ${ConfigLimits.MAX_FORWARDS})"
            }
            val forwards = buildList {
                for (i in 0 until forwardsArray.length()) {
                    val f = forwardsArray.getJSONObject(i)
                    add(
                        ForwardConfig(
                            localPort = f.getInt("localPort"),
                            remoteHost = f.getString("remoteHost"),
                            remotePort = f.getInt("remotePort"),
                            name = f.optString("name", ""),
                        )
                    )
                }
            }

            val appObj = obj.optJSONObject("app") ?: JSONObject()
            return AppConfig(
                schemaVersion = schema,
                ssh = ssh,
                forwards = forwards,
                app = AppSettings(logLimit = appObj.optInt("logLimit", 500)),
            )
        }
    }
}
