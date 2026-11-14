package dev.openwrtmanager.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.data.ProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceRow(val profile: Profile, val active: Boolean)

data class DevicesUi(val devices: List<DeviceRow> = emptyList(), val loaded: Boolean = false)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val profiles: ProfileRepository,
) : ViewModel() {
    val ui: StateFlow<DevicesUi> =
        combine(profiles.observeAll(), profiles.observeActiveId()) { all, activeId ->
            DevicesUi(all.map { DeviceRow(it, it.id == activeId) }, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUi())

    fun select(id: Long) {
        viewModelScope.launch { profiles.setActive(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { profiles.delete(id) }
    }
}
