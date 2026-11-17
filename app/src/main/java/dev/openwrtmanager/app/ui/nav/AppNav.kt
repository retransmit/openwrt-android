package dev.openwrtmanager.app.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.openwrtmanager.app.ui.devices.DevicesScreen
import dev.openwrtmanager.app.ui.devices.DevicesViewModel
import dev.openwrtmanager.app.ui.devices.EditDeviceScreen
import dev.openwrtmanager.app.ui.home.HomeScreen
import dev.openwrtmanager.app.ui.wifi.NetworkEditScreen
import dev.openwrtmanager.app.ui.wifi.WifiScreen
import dev.openwrtmanager.app.ui.wifi.WizardScreen
import dev.openwrtmanager.app.wifi.WizardKind

private const val ROUTE_MAIN = "main"
private const val ROUTE_DEVICES = "devices"
private const val ROUTE_EDIT = "device/edit?id={id}"
private const val NO_ID = -1L

private fun editRoute(id: Long?) = "device/edit?id=${id ?: NO_ID}"

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    NETWORK("Network", Icons.Default.Lan),
    WIFI("Wi-Fi", Icons.Default.Wifi),
    CLIENTS("Clients", Icons.Default.Devices),
    MORE("More", Icons.Default.MoreHoriz),
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            MainTabs(
                onManageDevices = { nav.navigate(ROUTE_DEVICES) },
                onAddDevice = { nav.navigate(editRoute(null)) },
                onEditNetwork = { section -> nav.navigate("wifi/network?section=${section.orEmpty()}") },
                onWizard = { kind -> nav.navigate("wifi/wizard/${kind.name}") },
                onMirror = { nav.navigate("wifi/mirror") },
                onFirewall = { nav.navigate("firewall") },
            )
        }
        composable(
            "wifi/network?section={section}",
            arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            NetworkEditScreen(
                section = entry.arguments?.getString("section")?.takeIf { it.isNotEmpty() },
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable("wifi/wizard/{kind}") { entry ->
            val kind = runCatching { WizardKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(WizardKind.GUEST)
            WizardScreen(kind = kind, onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable("firewall") {
            dev.openwrtmanager.app.ui.firewall.FirewallScreen(onBack = { nav.popBackStack() })
        }
        composable("wifi/mirror") {
            dev.openwrtmanager.app.ui.wifi.MirrorScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
        composable(ROUTE_DEVICES) {
            DevicesScreen(
                onAdd = { nav.navigate(editRoute(null)) },
                onEdit = { id -> nav.navigate(editRoute(id)) },
                onOpened = { nav.popBackStack(ROUTE_MAIN, inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            ROUTE_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = NO_ID }),
        ) { entry ->
            val id = entry.arguments?.getLong("id")?.takeIf { it != NO_ID }
            EditDeviceScreen(
                profileId = id,
                // A newly added device becomes the active one, so go straight to its Home screen.
                onDone = {
                    if (id == null) nav.popBackStack(ROUTE_MAIN, inclusive = false) else nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@Composable
private fun MainTabs(
    onManageDevices: () -> Unit,
    onAddDevice: () -> Unit,
    onEditNetwork: (String?) -> Unit,
    onWizard: (WizardKind) -> Unit,
    onMirror: () -> Unit,
    onFirewall: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { entry ->
                item(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    icon = { Icon(entry.icon, contentDescription = null) },
                    label = { Text(entry.label) },
                )
            }
        },
    ) {
        when (tab) {
            Tab.HOME -> HomeScreen(onSwitchDevice = { switcherOpen = true }, onAddDevice = onAddDevice)
            Tab.NETWORK -> dev.openwrtmanager.app.ui.network.VlanScreen()
            Tab.MORE -> dev.openwrtmanager.app.ui.more.MoreScreen(onFirewall = onFirewall)
            Tab.CLIENTS -> dev.openwrtmanager.app.ui.clients.ClientsScreen()
            Tab.WIFI -> WifiScreen(onEditNetwork = onEditNetwork, onWizard = onWizard, onMirror = onMirror)
            else -> ComingLater(tab)
        }
    }

    if (switcherOpen) {
        DeviceSwitcherSheet(
            onDismiss = { switcherOpen = false },
            onManageDevices = {
                switcherOpen = false
                onManageDevices()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSwitcherSheet(
    onDismiss: () -> Unit,
    onManageDevices: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Switch device",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ui.devices.forEach { row ->
                ListItem(
                    colors = transparent,
                    modifier = Modifier.clickable {
                        viewModel.select(row.profile.id)
                        onDismiss()
                    },
                    leadingContent = { Icon(Icons.Default.Router, contentDescription = null) },
                    headlineContent = { Text(row.profile.name) },
                    supportingContent = { Text(row.profile.host) },
                    trailingContent = {
                        if (row.active) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Current device",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
            HorizontalDivider()
            ListItem(
                colors = transparent,
                modifier = Modifier.clickable(onClick = onManageDevices),
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                headlineContent = { Text("Manage devices") },
            )
        }
    }
}

@Composable
private fun ComingLater(tab: Tab) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Text(tab.label, style = MaterialTheme.typography.titleLarge)
        Text(
            "Coming in a later update",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
