package dev.openwrtmanager.app.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.wifi.WifiConfig
import dev.openwrtmanager.app.wifi.WifiGatewayProvider
import dev.openwrtmanager.app.wifi.wifiConfigOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface WifiUi {
    data object Loading : WifiUi
    data object Offline : WifiUi
    data object Failed : WifiUi

    /** [canCreateIsolatedNetworks] is false on a device with no internet link of its own, such as an access point. */
    data class Loaded(val config: WifiConfig, val canCreateIsolatedNetworks: Boolean) : WifiUi
}

@HiltViewModel
class WifiViewModel @Inject constructor(
    private val gateways: WifiGatewayProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow<WifiUi>(WifiUi.Loading)
    val ui: StateFlow<WifiUi> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val gateway = gateways.current()
            if (gateway == null) {
                _ui.value = WifiUi.Offline
                return@launch
            }
            if (_ui.value !is WifiUi.Loaded) _ui.value = WifiUi.Loading
            _ui.value = try {
                val snapshot = gateway.snapshot()
                WifiUi.Loaded(wifiConfigOf(snapshot.wireless), snapshot.hasUplink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WifiUi.Failed
            }
        }
    }
}
