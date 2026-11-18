package dev.openwrtmanager.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.admin.AdminGatewayProvider
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.editor.EditorEdit
import dev.openwrtmanager.app.editor.EditorProblem
import dev.openwrtmanager.app.editor.planEdits
import dev.openwrtmanager.app.uci.UciConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorUi(
    val loading: Boolean = false,
    val offline: Boolean = false,
    val configs: List<String> = emptyList(),
    val config: UciConfig? = null,
    /** Staged, not yet on the device. */
    val edits: List<EditorEdit> = emptyList(),
    val message: String? = null,
)

@HiltViewModel
class EditorViewModel @Inject constructor(private val gateways: AdminGatewayProvider) : ViewModel() {
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    fun loadConfigs() {
        val gateway = gateways.current() ?: return _ui.update { it.copy(offline = true) }
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val configs = quietly { gateway.configs() }
            _ui.update { it.copy(loading = false, offline = false, configs = configs ?: it.configs, message = if (configs == null) "Could not list the config files." else null) }
        }
    }

    /** Opens one config file; staged edits of another file are dropped. */
    fun open(name: String) {
        val gateway = gateways.current() ?: return _ui.update { it.copy(offline = true) }
        if (_ui.value.config?.name != name) _ui.update { it.copy(edits = emptyList()) }
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val config = quietly { gateway.loadConfig(name) }
            _ui.update { it.copy(loading = false, config = config ?: it.config, message = if (config == null) "Could not read $name." else null) }
        }
    }

    fun stage(edit: EditorEdit) = _ui.update { it.copy(edits = it.edits + edit) }

    fun unstage(index: Int) = _ui.update { it.copy(edits = it.edits.filterIndexed { i, _ -> i != index }) }

    fun discard() = _ui.update { it.copy(edits = emptyList()) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** Reloads the file and plans the staged edits against it, so nothing is written to a section that is gone. */
    suspend fun review(): ChangeSet? {
        val name = _ui.value.config?.name ?: return null
        val gateway = gateways.current() ?: return null.also { say("Not connected to the device.") }
        val fresh = quietly { gateway.loadConfig(name) } ?: return null.also { say("Could not read $name.") }
        _ui.update { it.copy(config = fresh) }
        return when (val planned = planEdits(fresh, _ui.value.edits)) {
            is Planned.Ready -> planned.change
            is Planned.Problem -> null.also { say(problemText(planned.problem)) }
        }
    }

    /** After the change was applied: show the device's new state, nothing staged any more. */
    fun applied() {
        _ui.update { it.copy(edits = emptyList()) }
        _ui.value.config?.name?.let(::open)
    }

    private fun say(message: String) = _ui.update { it.copy(message = message) }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        fun problemText(problem: EditorProblem): String = when (problem) {
            EditorProblem.BAD_NAME -> "Names may only use letters, digits and underscores."
            EditorProblem.SECTION_GONE -> "A section you edited no longer exists on the device. Reload and try again."
            EditorProblem.SECTION_EXISTS -> "A section with that name already exists."
            EditorProblem.LINE_BREAK -> "Values cannot contain line breaks."
            EditorProblem.EMPTY_VALUE -> "A value cannot be empty. To clear an option, remove it instead."
            EditorProblem.NOTHING_TO_DO -> "These edits change nothing."
        }
    }
}
