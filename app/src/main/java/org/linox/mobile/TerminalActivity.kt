package org.linox.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalActivity : AppCompatActivity() {

    private var shellRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openLinuxShell()
    }

    private fun openLinuxShell() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val rootfs = withContext(Dispatchers.IO) {
                    LinuxRuntime.requireLinuxReady(this@TerminalActivity)
                }

                val shell = LinuxRuntime.resolveShell(rootfs)
                    ?: error("Shell validation failed")

                appendTerminal(
                    "[LinOx] Starting Linux shell: ${shell.absolutePath}\n"
                )

                startPtySession()
                shellRunning = true

            } catch (t: Throwable) {
                shellRunning = false

                val message = t.message ?: "Unknown Linux startup error"

                appendTerminal("[LinOx] $message\n")

                Toast.makeText(
                    this@TerminalActivity,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun startPtySession() = withContext(Dispatchers.IO) {
        val argv = LinuxRuntime.buildProotCommand(this@TerminalActivity)

        // Replace with your existing PTY bridge call:
        // ptyProcess = PtyBridge.start(argv)

        require(argv.isNotEmpty()) {
            "Failed to build PRoot command"
        }
    }

    private fun appendTerminal(text: String) {
        // Keep your existing terminal output implementation.
    }
}
