package com.example.sshlink

/** Hard resource limits for configuration storage/import and UI rendering. */
object ConfigLimits {
    const val MAX_IMPORT_BYTES = 1024 * 1024
    const val MAX_FORWARDS = 128
    const val MAX_SSH_HOST_CHARS = 253
    const val MAX_USERNAME_CHARS = 128
    const val MAX_REMOTE_HOST_CHARS = 253
    const val MAX_FORWARD_NAME_CHARS = 64
}
