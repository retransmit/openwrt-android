package dev.openwrtmanager.app.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.ssh.SshShell
import dev.openwrtmanager.app.terminal.TerminalSessionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TerminalViewModel @Inject constructor(private val opener: ShellOpener) : ViewModel() {
    private val _ui = MutableStateFlow<TerminalUi>(TerminalUi.Idle)
    val ui: StateFlow<TerminalUi> = _ui.asStateFlow()

    private var cols = 80
    private var rows = 24

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

    /** A tap while a shell is already opening is ignored, so two taps never open two connections. */
    fun reconnect() {
        if (_ui.value == TerminalUi.Connecting) return
        close()
        open()
    }

    fun close() {
        opening?.cancel()
        opening = null
        (_ui.value as? TerminalUi.Open)?.session?.close()
        _ui.value = TerminalUi.Idle
    }

    private var opening: Job? = null

    private fun open() {
        _ui.value = TerminalUi.Connecting
        val openCols = cols
        val openRows = rows
        // Connecting runs on its own, so leaving the screen cannot drop a shell that is half open:
        // if nobody is waiting when it arrives, it is closed together with its connection.
        val pending = CoroutineScope(Dispatchers.IO).async { opener.open(openCols, openRows) }
        opening = viewModelScope.launch {
            val shell = try {
                pending.await()
            } catch (e: CancellationException) {
                pending.invokeOnCompletion { failure ->
                    if (failure == null) runCatching { pending.getCompleted()?.close() }
                }
                throw e
            } catch (e: Exception) {
                _ui.value = TerminalUi.Failed("Could not open a shell on the device.")
                return@launch
            }
            if (shell == null) {
                _ui.value = TerminalUi.Failed("Connect to a device on the Home tab first.")
                return@launch
            }
            val session = TerminalSessionModel(shell, openCols, openRows, viewModelScope)
            // The screen may have changed size while the shell was opening.
            if (cols != openCols || rows != openRows) session.resize(cols, rows)
            _ui.value = TerminalUi.Open(session)
        }
    }

    override fun onCleared() {
        opening?.cancel()
        (_ui.value as? TerminalUi.Open)?.session?.close()
    }
}
