package dev.openwrtmanager.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.ListRow
import dev.openwrtmanager.app.ui.kit.RowDivider

/** Everything that is not a main tab, grouped in M3 tonal containers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onFirewall: () -> Unit,
    onServices: () -> Unit,
    onLogs: () -> Unit,
    onSystem: () -> Unit,
    onPackages: () -> Unit,
    onBackup: () -> Unit,
    onTerminal: () -> Unit,
    onEditor: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("More") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ListCard(label = "Network and security") {
                ListRow("Firewall", icon = Icons.Outlined.Security, detail = "Port forwards, traffic rules and zones", onClick = onFirewall)
                RowDivider()
                ListRow("Config editor", icon = Icons.Outlined.EditNote, detail = "Every UCI setting, file by file, with review", onClick = onEditor)
            }
            ListCard(label = "System and maintenance") {
                ListRow("System", icon = Icons.Outlined.Settings, detail = "Device name, time zone, root password, reboot", onClick = onSystem)
                RowDivider()
                ListRow("Services", icon = Icons.Outlined.PlayCircle, detail = "Start, stop and enable init scripts", onClick = onServices)
                RowDivider()
                ListRow("Logs", icon = Icons.AutoMirrored.Outlined.Article, detail = "System and kernel log, live", onClick = onLogs)
                RowDivider()
                ListRow("Packages", icon = Icons.Outlined.Inventory2, detail = "Installed software, install and remove", onClick = onPackages)
                RowDivider()
                ListRow("Backup", icon = Icons.Outlined.Backup, detail = "Save settings to this phone, or restore them", onClick = onBackup)
            }
            ListCard(label = "Advanced") {
                ListRow("Terminal", icon = Icons.Outlined.Terminal, detail = "Interactive SSH shell on the device", onClick = onTerminal)
            }
        }
    }
}
