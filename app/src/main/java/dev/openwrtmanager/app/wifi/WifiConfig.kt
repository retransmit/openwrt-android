package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.uci.UciConfig

enum class Security(val uci: String) {
    OPEN("none"),
    WPA2("psk2"),
    WPA2_WPA3("sae-mixed"),
    WPA3("sae");

    companion object {
        /** Null for modes the app does not offer (WEP, enterprise, OWE): they are shown but left alone. */
        fun fromUci(value: String?): Security? {
            val mode = value?.substringBefore('+')
            return when (mode) {
                null, "", "none" -> OPEN
                "psk2", "psk-mixed", "psk" -> WPA2
                "sae-mixed" -> WPA2_WPA3
                "sae" -> WPA3
                else -> null
            }
        }
    }
}

data class Radio(
    val name: String,
    val band: Band,
    val channel: String,
    val htmode: String,
    val country: String?,
    val txpower: Int?,
    val disabled: Boolean,
)

data class WifiIface(
    val section: String,
    val radio: String,
    val ssid: String,
    val security: Security?,
    val rawEncryption: String,
    val key: String?,
    val network: String?,
    val hidden: Boolean,
    val isolate: Boolean,
    val disabled: Boolean,
    val ieee80211k: Boolean,
    val bssTransition: Boolean,
    val ieee80211r: Boolean,
) {
    override fun toString(): String = "WifiIface($section on $radio, ssid=$ssid, $rawEncryption)"
}

/** Same SSID, security, key and network on one or more radios: what the user calls one network. */
data class WifiGroup(val ssid: String, val ifaces: List<WifiIface>, val bands: List<Band>) {
    val first: WifiIface get() = ifaces.first()

    /** Roaming assistance (802.11k and BSS transition) on every radio, and more than one band to move between. */
    val steeringAssist: Boolean
        get() = bands.size > 1 && ifaces.all { it.ieee80211k && it.bssTransition }

    val fastRoaming: Boolean get() = ifaces.all { it.ieee80211r }

    val enabled: Boolean get() = ifaces.any { !it.disabled }

    override fun toString(): String = "WifiGroup($ssid, ${ifaces.map { it.section }})"
}

data class WifiConfig(val radios: List<Radio>, val ifaces: List<WifiIface>) {
    val groups: List<WifiGroup> by lazy {
        ifaces.groupBy { GroupKey(it.ssid, it.rawEncryption, it.key, it.network) }.map { (key, members) ->
            val bands = members.mapNotNull { iface -> radios.firstOrNull { it.name == iface.radio }?.band }
            WifiGroup(key.ssid, members, bands.distinct().sortedBy { it.ordinal })
        }
    }

    fun radio(name: String): Radio? = radios.firstOrNull { it.name == name }

    override fun toString(): String = "WifiConfig(radios=${radios.map { it.name }}, ifaces=$ifaces)"

    private data class GroupKey(val ssid: String, val encryption: String, val key: String?, val network: String?)
}

/** Builds the Wi-Fi model from the `wireless` config. Only access point interfaces are included. */
fun wifiConfigOf(wireless: UciConfig): WifiConfig {
    val radios = wireless.ofType("wifi-device").map { section ->
        val band = when (section.opt("band")) {
            "2g" -> Band.GHZ_2_4
            "5g" -> Band.GHZ_5
            "6g" -> Band.GHZ_6
            null -> when {
                section.opt("hwmode") == null -> Band.UNKNOWN
                section.opt("hwmode")!!.contains('a') -> Band.GHZ_5
                else -> Band.GHZ_2_4
            }
            else -> Band.UNKNOWN
        }
        Radio(
            name = section.name,
            band = band,
            channel = section.opt("channel") ?: "auto",
            htmode = section.opt("htmode").orEmpty(),
            country = section.opt("country"),
            txpower = section.opt("txpower")?.toIntOrNull(),
            disabled = section.flag("disabled"),
        )
    }
    val ifaces = wireless.ofType("wifi-iface")
        .filter { (it.opt("mode") ?: "ap") == "ap" }
        .mapNotNull { section ->
            val radio = section.opt("device") ?: return@mapNotNull null
            val encryption = section.opt("encryption") ?: "none"
            WifiIface(
                section = section.name,
                radio = radio,
                ssid = section.opt("ssid").orEmpty(),
                security = Security.fromUci(encryption),
                rawEncryption = encryption,
                key = section.opt("key"),
                network = section.opt("network"),
                hidden = section.flag("hidden"),
                isolate = section.flag("isolate"),
                disabled = section.flag("disabled"),
                ieee80211k = section.flag("ieee80211k"),
                bssTransition = section.flag("bss_transition"),
                ieee80211r = section.flag("ieee80211r"),
            )
        }
    return WifiConfig(radios, ifaces)
}
