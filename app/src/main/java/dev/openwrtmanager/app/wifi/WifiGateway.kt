package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.uci.UciClient

/** Everything the Wi-Fi screens read from, or ask of, the current device. None of it changes network config. */
interface WifiGateway {
    suspend fun wifi(): WifiConfig

    suspend fun snapshot(): DeviceSnapshot

    suspend fun usteerStatus(): UsteerStatus

    suspend fun startUsteer(): Boolean

    suspend fun installUsteer(): Boolean
}

fun interface WifiGatewayProvider {
    /** Null when no device is connected. */
    fun current(): WifiGateway?
}

class SshWifiGateway(ssh: SshExecutor) : WifiGateway {
    private val uci = UciClient(ssh)
    private val router = RouterClient(ssh)
    private val steering = SteeringClient(ssh)

    override suspend fun wifi(): WifiConfig = wifiConfigOf(uci.load("wireless"))

    override suspend fun snapshot(): DeviceSnapshot = loadSnapshot(router, uci)

    override suspend fun usteerStatus(): UsteerStatus = steering.usteerStatus()

    override suspend fun startUsteer(): Boolean = steering.startUsteer()

    override suspend fun installUsteer(): Boolean = steering.installUsteer(steering.packageManager())
}
