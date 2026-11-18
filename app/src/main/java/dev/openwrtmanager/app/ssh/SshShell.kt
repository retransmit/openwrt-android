package dev.openwrtmanager.app.ssh

import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.io.InputStream
import java.io.OutputStream

/** An interactive shell on a pseudo terminal. Reading and writing block, so use them off the main thread. */
interface SshShell {
    val input: InputStream
    val output: OutputStream
    val isOpen: Boolean

    /** Tells the device the terminal's new size in characters. */
    fun resize(cols: Int, rows: Int)

    fun close()
}

internal class SshjShell(private val session: Session, private val shell: Session.Shell) : SshShell {
    override val input: InputStream get() = shell.inputStream
    override val output: OutputStream get() = shell.outputStream
    override val isOpen: Boolean get() = shell.isOpen

    override fun resize(cols: Int, rows: Int) {
        (session as? SessionChannel)?.changeWindowDimensions(cols, rows, 0, 0)
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}
