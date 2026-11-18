package dev.openwrtmanager.app.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.ssh.SshShell
import dev.openwrtmanager.app.terminal.TerminalSessionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Opens a shell on the current device over a connection of its own. Null when no device is connected. */
fun interface ShellOpener {
    suspend fun open(cols: Int, rows: Int): SshShell?
}

sealed interface TerminalUi {
    data object Idle : TerminalUi
    data object Connecting : TerminalUi
    data class Open(val session: TerminalSessionModel) : TerminalUi
    data class Failed(val message: String) : TerminalUi
}

@HiltViewModel
class TerminalViewModel @Inject constructor(private val opener: ShellOpener) : ViewModel() {
    private val _ui = MutableStateFlow<TerminalUi>(TerminalUi.Idle)
    val ui: StateFlow<TerminalUi> = _ui.asStateFlow()

    private var cols = 80
    private var rows = 24

    /** Opens the shell once the screen knows how many columns and rows fit. */
    /** The screen's current size: opens the shell the first time, and resizes it after that. */
    fun start(cols: Int, rows: Int) {
        if (_ui.value == TerminalUi.Idle) {
            this.cols = cols
            this.rows = rows
            open()
        } else {
            resize(cols, rows)
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        (_ui.value as? TerminalUi.Open)?.session?.resize(cols, rows)
    }

    fun reconnect() {
        close()
        open()
    }

    fun close() {
        (_ui.value as? TerminalUi.Open)?.session?.close()
        _ui.value = TerminalUi.Idle
    }

    private fun open() {
        _ui.value = TerminalUi.Connecting
        val openCols = cols
        val openRows = rows
        viewModelScope.launch {
            val shell = try {
                withContext(Dispatchers.IO) { opener.open(openCols, openRows) }
                    ?: return@launch run { _ui.value = TerminalUi.Failed("Connect to a device on the Home tab first.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = TerminalUi.Failed("Could not open a shell on the device.")
                return@launch
            }
            val session = TerminalSessionModel(shell, openCols, openRows, viewModelScope)
            // The screen may have changed size while the shell was opening.
            if (cols != openCols || rows != openRows) session.resize(cols, rows)
            _ui.value = TerminalUi.Open(session)
        }
    }

    override fun onCleared() {
        (_ui.value as? TerminalUi.Open)?.session?.close()
    }
}
