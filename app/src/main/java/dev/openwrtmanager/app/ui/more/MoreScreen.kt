package dev.openwrtmanager.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.ListRow
import dev.openwrtmanager.app.ui.kit.RowDivider

/** Everything that is not a main tab, grouped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(onFirewall: () -> Unit, onServices: () -> Unit, onLogs: () -> Unit, onSystem: () -> Unit, onPackages: () -> Unit, onBackup: () -> Unit, onTerminal: () -> Unit, onEditor: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListCard(label = "Network and security") {
                ListRow("Firewall", icon = Icons.Default.Security, detail = "Port forwards, traffic rules and zones", onClick = onFirewall)
                RowDivider()
                ListRow("Config editor", icon = Icons.Default.EditNote, detail = "Every setting, file by file, with review", onClick = onEditor)
            }
            ListCard(label = "Maintenance") {
                ListRow("System", icon = Icons.Default.Settings, detail = "Device name, time zone, root password, reboot", onClick = onSystem)
                RowDivider()
                ListRow("Services", icon = Icons.Default.PlayCircle, detail = "Start, stop and enable what runs on the device", onClick = onServices)
                RowDivider()
                ListRow("Logs", icon = Icons.AutoMirrored.Filled.Article, detail = "System and kernel log, live", onClick = onLogs)
                RowDivider()
                ListRow("Packages", icon = Icons.Default.Inventory2, detail = "Installed software, install and remove", onClick = onPackages)
                RowDivider()
                ListRow("Backup", icon = Icons.Default.Backup, detail = "Save settings to this phone, or restore them", onClick = onBackup)
                RowDivider()
                ListRow("Terminal", icon = Icons.Default.Terminal, detail = "A shell on the device", onClick = onTerminal)
            }
        }
    }
}
