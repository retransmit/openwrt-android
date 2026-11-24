package dev.openwrtmanager.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.openwrtmanager.app.ui.admin.BackupScreen
import dev.openwrtmanager.app.ui.admin.LogsScreen
import dev.openwrtmanager.app.ui.admin.PackagesScreen
import dev.openwrtmanager.app.ui.admin.ServicesScreen
import dev.openwrtmanager.app.ui.admin.SystemScreen
import dev.openwrtmanager.app.ui.clients.ClientsScreen
import dev.openwrtmanager.app.ui.devices.DevicesScreen
import dev.openwrtmanager.app.ui.devices.DevicesViewModel
import dev.openwrtmanager.app.ui.devices.EditDeviceScreen
import dev.openwrtmanager.app.ui.editor.ConfigEditScreen
import dev.openwrtmanager.app.ui.editor.ConfigListScreen
import dev.openwrtmanager.app.ui.firewall.FirewallScreen
import dev.openwrtmanager.app.ui.home.HomeScreen
import dev.openwrtmanager.app.ui.kit.IconBadge
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.more.MoreScreen
import dev.openwrtmanager.app.ui.network.VlanScreen
import dev.openwrtmanager.app.ui.terminal.TerminalScreen
import dev.openwrtmanager.app.ui.wifi.MirrorScreen
import dev.openwrtmanager.app.ui.wifi.NetworkEditScreen
import dev.openwrtmanager.app.ui.wifi.WifiScreen
import dev.openwrtmanager.app.ui.wifi.WizardScreen
import dev.openwrtmanager.app.wifi.WizardKind

private const val ROUTE_MAIN = "main"
private const val ROUTE_DEVICES = "devices"
private const val ROUTE_EDIT = "device/edit?id={id}"
private const val NO_ID = -1L

private fun editRoute(id: Long?) = "device/edit?id=${id ?: NO_ID}"

private const val NAV_MS = 260

private enum class Tab(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    NETWORK("Network", Icons.Filled.Lan, Icons.Outlined.Lan),
    WIFI("Wi-Fi", Icons.Filled.Wifi, Icons.Outlined.Wifi),
    CLIENTS("Clients", Icons.Filled.Devices, Icons.Outlined.Devices),
    MORE("More", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = ROUTE_MAIN,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = { slideInHorizontally(tween(NAV_MS)) { it / 10 } + fadeIn(tween(NAV_MS)) },
        exitTransition = { slideOutHorizontally(tween(NAV_MS)) { -it / 10 } + fadeOut(tween(NAV_MS / 2)) },
        popEnterTransition = { slideInHorizontally(tween(NAV_MS)) { -it / 10 } + fadeIn(tween(NAV_MS)) },
        popExitTransition = { slideOutHorizontally(tween(NAV_MS)) { it / 10 } + fadeOut(tween(NAV_MS / 2)) },
    ) {
        composable(ROUTE_MAIN) {
            MainTabs(
                onManageDevices = { nav.navigate(ROUTE_DEVICES) },
                onAddDevice = { nav.navigate(editRoute(null)) },
                onEditNetwork = { section -> nav.navigate("wifi/network?section=${section.orEmpty()}") },
                onWizard = { kind -> nav.navigate("wifi/wizard/${kind.name}") },
                onMirror = { nav.navigate("wifi/mirror") },
                onFirewall = { nav.navigate("firewall") },
                onServices = { nav.navigate("services") },
                onLogs = { nav.navigate("logs") },
                onSystem = { nav.navigate("system") },
                onPackages = { nav.navigate("packages") },
                onBackup = { nav.navigate("backup") },
                onTerminal = { nav.navigate("terminal") },
                onEditor = { nav.navigate("editor") },
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
        composable("services") { ServicesScreen(onBack = { nav.popBackStack() }) }
        composable("logs") { LogsScreen(onBack = { nav.popBackStack() }) }
        composable("packages") { PackagesScreen(onBack = { nav.popBackStack() }) }
        composable("backup") { BackupScreen(onBack = { nav.popBackStack() }) }
        composable("editor") { ConfigListScreen(onOpen = { nav.navigate("editor/$it") }, onBack = { nav.popBackStack() }) }
        composable("editor/{config}") { entry -> ConfigEditScreen(name = entry.arguments?.getString("config").orEmpty(), onBack = { nav.popBackStack() }) }
        composable("terminal") { TerminalScreen(onBack = { nav.popBackStack() }) }
        composable("system") { SystemScreen(onBack = { nav.popBackStack() }) }
        composable("firewall") { FirewallScreen(onBack = { nav.popBackStack() }) }
        composable("wifi/mirror") { MirrorScreen(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
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
    onServices: () -> Unit,
    onLogs: () -> Unit,
    onSystem: () -> Unit,
    onPackages: () -> Unit,
    onBackup: () -> Unit,
    onTerminal: () -> Unit,
    onEditor: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = scheme.secondaryContainer,
            selectedIconColor = scheme.onSecondaryContainer,
            selectedTextColor = scheme.onSurface,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = scheme.secondaryContainer,
            selectedIconColor = scheme.onSecondaryContainer,
            selectedTextColor = scheme.onSurface,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
    )
    NavigationSuiteScaffold(
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = scheme.surfaceContainer,
            navigationRailContainerColor = scheme.surfaceContainer,
        ),
        navigationSuiteItems = {
            Tab.entries.forEach { entry ->
                val selected = tab == entry
                item(
                    selected = selected,
                    onClick = { tab = entry },
                    icon = { Icon(if (selected) entry.selectedIcon else entry.unselectedIcon, contentDescription = null) },
                    label = { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = itemColors,
                )
            }
        },
    ) {
        when (tab) {
            Tab.HOME -> HomeScreen(onSwitchDevice = { switcherOpen = true }, onAddDevice = onAddDevice)
            Tab.NETWORK -> VlanScreen()
            Tab.WIFI -> WifiScreen(onEditNetwork = onEditNetwork, onWizard = onWizard, onMirror = onMirror)
            Tab.CLIENTS -> ClientsScreen()
            Tab.MORE -> MoreScreen(
                onFirewall = onFirewall,
                onServices = onServices,
                onLogs = onLogs,
                onSystem = onSystem,
                onPackages = onPackages,
                onBackup = onBackup,
                onTerminal = onTerminal,
                onEditor = onEditor,
            )
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
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Switch device", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
            ui.devices.forEach { row ->
                val container = if (row.active) scheme.secondaryContainer else scheme.surfaceContainerHigh
                val content = if (row.active) scheme.onSecondaryContainer else scheme.onSurface
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = container,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.select(row.profile.id)
                            onDismiss()
                        },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        IconBadge(
                            Icons.Default.Router,
                            tint = if (row.active) scheme.onPrimaryContainer else scheme.primary,
                            containerColor = if (row.active) scheme.primaryContainer else scheme.surfaceContainerLowest,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.profile.name, style = MaterialTheme.typography.titleSmall, color = content)
                            val addr = if (row.profile.port == 22) row.profile.host else "${row.profile.host}:${row.profile.port}"
                            MonoText("${row.profile.username}@$addr", color = content.copy(alpha = 0.78f))
                        }
                        if (row.active) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Current device", tint = scheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = onManageDevices, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Manage devices")
            }
        }
    }
}
