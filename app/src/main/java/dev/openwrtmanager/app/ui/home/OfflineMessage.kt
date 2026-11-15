package dev.openwrtmanager.app.ui.home

import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.SshError

data class OfflineMessage(val title: String, val detail: String)

/** What to tell the user when the active device cannot be used. Each cause gets its own wording. */
fun offlineMessage(error: Throwable): OfflineMessage = when (error) {
    is SshError.AuthFailed -> OfflineMessage(
        "Login was refused",
        "The saved password or key no longer works. Edit the device to fix it.",
    )
    is SshError.HostKeyChanged -> OfflineMessage(
        "This device's identity has changed",
        "The app stopped connecting to be safe. Edit the device and test it again to review the new fingerprint.",
    )
    is SshError.HostKeyUnknown -> OfflineMessage(
        "This device is not trusted yet",
        "Edit the device and test it to confirm its fingerprint.",
    )
    is SshError.Timeout -> OfflineMessage(
        "The device is slow to answer",
        "It accepted the connection but did not reply in time. Trying again automatically.",
    )
    is RouterError.NotOpenWrt -> OfflineMessage(
        "This does not look like OpenWrt",
        "The device has no ubus command.",
    )
    is RouterError.CommandFailed -> OfflineMessage(
        "The device refused a status request",
        "The login may not have permission to read system status. Use the root user.",
    )
    is RouterError.BadResponse -> OfflineMessage(
        "The device gave an unexpected answer",
        "Its status output could not be read. Trying again automatically.",
    )
    else -> OfflineMessage(
        "Cannot reach the device",
        "Trying again automatically. Check that this phone is on the right network.",
    )
}
