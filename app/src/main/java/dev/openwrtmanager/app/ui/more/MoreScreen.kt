package dev.openwrtmanager.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
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
fun MoreScreen(onFirewall: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                modifier = Modifier.clickable(onClick = onFirewall),
                leadingContent = { Icon(Icons.Default.Security, contentDescription = null) },
                headlineContent = { Text("Firewall") },
                supportingContent = { Text("Port forwards, traffic rules and zones") },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
            )
        }
    }
}
