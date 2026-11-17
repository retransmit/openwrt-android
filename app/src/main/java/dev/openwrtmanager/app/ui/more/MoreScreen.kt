package dev.openwrtmanager.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Everything that is not a main tab. Device admin and the config editor join this list later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(onFirewall: () -> Unit, onServices: () -> Unit, onLogs: () -> Unit, onSystem: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Entry(Icons.Default.Security, "Firewall", "Port forwards, traffic rules and zones", onFirewall)
            Entry(Icons.Default.Settings, "System", "Device name, time zone, root password, reboot", onSystem)
            Entry(Icons.Default.PlayCircle, "Services", "Start, stop and enable what runs on the device", onServices)
            Entry(Icons.AutoMirrored.Filled.Article, "Logs", "System and kernel log, live", onLogs)
        }
    }
}

@Composable
private fun Entry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
    )
}
