package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch

/** What the user wants a network to look like. [key] is ignored for open networks. */
data class NetworkEdit(
    val ssid: String,
    val security: Security,
    val key: String?,
    val hidden: Boolean,
    val isolate: Boolean,
    val enabled: Boolean,
    val radios: Set<String>,
) {
    override fun toString(): String = "NetworkEdit($ssid, $security, radios=$radios)"
}

/** Pure builders that turn an intended Wi-Fi change into a reviewable ChangeSet. Nothing here talks to a router. */
object WifiEdits {
    private const val W = "wireless"

    fun editGroup(config: WifiConfig, group: WifiGroup, edit: NetworkEdit): ChangeSet {
        val batch = UciBatch()
        val summary = mutableListOf<String>()
        val old = group.first
        val name = quoted(group.ssid)
        val kept = group.ifaces.filter { it.radio in edit.radios }
        val securityChanged = old.security != edit.security
        val newKey = edit.key.takeIf { edit.security != Security.OPEN }

        for (iface in kept) {
            val s = iface.section
            if (iface.ssid != edit.ssid) batch.set(W, s, "ssid", edit.ssid)
            if (securityChanged) {
                batch.set(W, s, "encryption", edit.security.uci)
                batch.set(W, s, "ieee80211w", managementFrameProtection(edit.security))
            }
            if (edit.security == Security.OPEN) {
                if (iface.key != null) batch.delete(W, s, "key")
            } else if (newKey != null && newKey != iface.key) {
                batch.set(W, s, "key", newKey)
            }
            if (iface.hidden != edit.hidden) batch.set(W, s, "hidden", flag(edit.hidden))
            if (iface.isolate != edit.isolate) batch.set(W, s, "isolate", flag(edit.isolate))
            if (iface.disabled == edit.enabled) batch.set(W, s, "disabled", flag(!edit.enabled))
        }
        if (kept.isNotEmpty()) {
            if (old.ssid != edit.ssid) summary += "Rename $name to ${quoted(edit.ssid)}"
            if (securityChanged) summary += "Change the security of $name to ${edit.security.label()}"
            if (edit.security != Security.OPEN && newKey != null && newKey != old.key) summary += "Change the password of $name"
            if (old.hidden != edit.hidden) {
                summary += if (edit.hidden) "Hide $name from network lists" else "Show $name in network lists"
            }
            if (old.isolate != edit.isolate) {
                summary += if (edit.isolate) "Stop devices on $name from seeing each other" else "Let devices on $name see each other"
            }
            if (group.enabled != edit.enabled) summary += if (edit.enabled) "Turn on $name" else "Turn off $name"
        }

        for (iface in group.ifaces.filter { it.radio !in edit.radios }) {
            batch.delete(W, iface.section)
            summary += "Stop broadcasting $name on ${bandLabel(config, iface.radio)}"
        }
        val taken = config.sectionNames.toMutableSet()
        for (radio in edit.radios - group.ifaces.map { it.radio }.toSet()) {
            newIface(batch, freeName(taken, "wifinet_$radio"), radio, edit, old.network)
            summary += "Also broadcast ${quoted(edit.ssid)} on ${bandLabel(config, radio)}"
        }
        return ChangeSet.of("Edit ${quoted(group.ssid)}", summary, batch)
    }

    fun addNetwork(config: WifiConfig, edit: NetworkEdit, network: String): ChangeSet {
        val batch = UciBatch()
        val taken = config.sectionNames.toMutableSet()
        val radios = config.radios.map { it.name }.filter { it in edit.radios }
        for (radio in radios) newIface(batch, freeName(taken, "wifinet_$radio"), radio, edit, network)
        val bands = radios.joinToString(" and ") { bandLabel(config, it) }
        val summary = if (radios.isEmpty()) emptyList() else listOf("Add network ${quoted(edit.ssid)} on $bands")
        return ChangeSet.of("Add ${quoted(edit.ssid)}", summary, batch)
    }

    fun deleteGroup(config: WifiConfig, group: WifiGroup): ChangeSet {
        val batch = UciBatch()
        group.ifaces.forEach { batch.delete(W, it.section) }
        val summary = mutableListOf("Remove ${quoted(group.ssid)}")
        if (config.ifaces.size == group.ifaces.size) summary += "This device will have no Wi-Fi networks left"
        return ChangeSet.of("Remove ${quoted(group.ssid)}", summary, batch)
    }

    fun editRadio(radio: Radio, channel: String, htmode: String, txpower: Int?, country: String?, enabled: Boolean): ChangeSet {
        val batch = UciBatch()
        val summary = mutableListOf<String>()
        val name = "the ${radio.band.label()} radio"
        if (channel != radio.channel) {
            batch.set(W, radio.name, "channel", channel)
            summary += if (channel == "auto") "Let $name pick its channel" else "Set $name to channel $channel"
        }
        if (htmode.isNotEmpty() && htmode != radio.htmode) {
            batch.set(W, radio.name, "htmode", htmode)
            summary += "Set $name width mode to $htmode"
        }
        if (txpower != radio.txpower) {
            if (txpower == null) {
                batch.delete(W, radio.name, "txpower")
                summary += "Let $name use its default power"
            } else {
                batch.set(W, radio.name, "txpower", txpower.toString())
                summary += "Set $name power to $txpower dBm"
            }
        }
        if (country != radio.country && !country.isNullOrEmpty()) {
            batch.set(W, radio.name, "country", country)
            summary += "Set $name country to $country"
        }
        if (radio.disabled == enabled) {
            batch.set(W, radio.name, "disabled", flag(!enabled))
            summary += if (enabled) "Turn on $name" else "Turn off $name"
        }
        return ChangeSet.of("Edit $name", summary, batch)
    }

    /** Writes a complete new access point section. Shared with the network wizard and steering. */
    internal fun newIface(batch: UciBatch, section: String, radio: String, edit: NetworkEdit, network: String?) {
        batch.section(W, section, "wifi-iface")
        batch.set(W, section, "device", radio)
        batch.set(W, section, "mode", "ap")
        if (network != null) batch.set(W, section, "network", network)
        batch.set(W, section, "ssid", edit.ssid)
        batch.set(W, section, "encryption", edit.security.uci)
        if (edit.security != Security.OPEN && edit.key != null) batch.set(W, section, "key", edit.key)
        if (edit.security == Security.WPA3 || edit.security == Security.WPA2_WPA3) {
            batch.set(W, section, "ieee80211w", managementFrameProtection(edit.security))
        }
        if (edit.hidden) batch.set(W, section, "hidden", "1")
        if (edit.isolate) batch.set(W, section, "isolate", "1")
        if (!edit.enabled) batch.set(W, section, "disabled", "1")
    }

    /** Picks [base], or [base]_2, _3 and so on, and reserves it. */
    internal fun freeName(taken: MutableSet<String>, base: String): String {
        var name = base
        var n = 2
        while (name in taken) name = "${base}_${n++}"
        taken += name
        return name
    }

    private fun managementFrameProtection(security: Security): String = when (security) {
        Security.WPA3 -> "2"
        Security.WPA2_WPA3 -> "1"
        else -> "0"
    }

    private fun bandLabel(config: WifiConfig, radio: String): String = config.radio(radio)?.band?.label() ?: radio

    private fun flag(on: Boolean) = if (on) "1" else "0"

    private fun quoted(text: String) = "\"$text\""
}

fun Security.label(): String = when (this) {
    Security.OPEN -> "open (no password)"
    Security.WPA2 -> "WPA2"
    Security.WPA2_WPA3 -> "WPA2 and WPA3"
    Security.WPA3 -> "WPA3 only"
}
