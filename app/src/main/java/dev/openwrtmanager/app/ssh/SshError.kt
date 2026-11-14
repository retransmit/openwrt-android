package dev.openwrtmanager.app.ssh

sealed class SshError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(cause: Throwable? = null) : SshError("unreachable", cause)
    class AuthFailed : SshError("auth failed")
    class HostKeyChanged(val expected: String, val actual: String) : SshError("host key changed")
    class HostKeyUnknown(val fingerprint: String) : SshError("host key unknown")
    class Timeout : SshError("timeout")
    class Disconnected(cause: Throwable? = null) : SshError("disconnected", cause)
}
