package dev.openwrtmanager.app.ui.kit

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import dev.openwrtmanager.app.ui.theme.OpenWrtTheme

@Composable
private fun KitSampler() {
    Column(
        Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(label = "Internet", trailing = { LiveMarker() }, glow = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Readout("4.2", unit = "Mb/s", color = MaterialTheme.colorScheme.primary, size = 24.sp)
                Readout("0.6", unit = "Mb/s", color = MaterialTheme.colorScheme.tertiary, size = 24.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("RAM", "61", Modifier.weight(1f), unit = "%", usage = 61)
            StatTile("Flash", "12", Modifier.weight(1f), unit = "%", usage = 12, detail = "7.8 MB free")
            StatTile("Clients", "12", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(LocalExtraColors.current.online, glow = true)
            TagChip("2.4 GHz")
            TagChip("Connected", tone = ChipTone.OK)
            TagChip("DHCP off", tone = ChipTone.WARNING)
            TagChip("Blocked", tone = ChipTone.ERROR)
        }
        ListCard(label = "Maintenance") {
            ListRow("System", icon = Icons.Default.Settings, detail = "Device name, time zone", onClick = {})
            RowDivider()
            ListRow("Home", icon = Icons.Default.Wifi, detail = "192.168.1.1", monoDetail = true, onClick = {})
        }
    }
}


@Preview(name = "Kit dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun KitDark() = OpenWrtTheme { KitSampler() }

@Preview(name = "Kit light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun KitLight() = OpenWrtTheme { KitSampler() }
