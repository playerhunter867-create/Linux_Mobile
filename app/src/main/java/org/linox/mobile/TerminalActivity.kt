package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.linox.mobile.terminal.TerminalView
import java.util.concurrent.Executors

/**
 * Interactive Linux terminal. The PTY is real; TerminalView handles ANSI text
 * instead of presenting shell output as a plain Android TextView.
 */
class TerminalActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var terminal: TerminalView
    private lateinit var input: EditText
    private var session: PtySession? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = LinuxRuntime(this)
        buildUi()
        if (runtime.isLinuxReady()) startShell()
        else showSetupMessage()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 9, 11))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(Color.rgb(16, 19, 24))
        }

        val title = TextView(this).apply {
            text = "🐧 Linux Terminal"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        bar.addView(smallButton("Ctrl+C") {
            session?.interrupt()
        })
        bar.addView(smallButton("Clear") {
            terminal.feed("\u001b[2J\u001b[H")
        })
        bar.addView(smallButton("Exit") { finish() })

        root.addView(bar)

        terminal = TerminalView(this).apply {
            setBackgroundColor(Color.rgb(7, 9, 11))
            setPadding(6, 6, 6, 6)
        }
        root.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 6, 6, 6)
            setBackgroundColor(Color.rgb(16, 19, 24))
        }

        input = EditText(this).apply {
            hint = "Type command…"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnEditorActionListener { _, _, _ ->
                sendInput()
                true
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        inputRow.addView(smallButton("↵") { sendInput() })
        root.addView(inputRow)

        setContentView(root)
    }

    private fun startShell() {
        executor.execute {
            try {
                session = runtime.startInteractivePty { text ->
                    runOnUiThread { terminal.feed(text) }
                }
                runOnUiThread {
                    terminal.feed("\n[LinOx] Linux shell started\n")
                    input.requestFocus()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    terminal.feed(
                        "\n[LinOx] Failed to start shell: " +
                            (t.message ?: t.javaClass.simpleName) + "\n"
                    )
                }
            }
        }
    }

    private fun showSetupMessage() {
        terminal.feed(
            "LinOx Mobile 0.9\n\n" +
                "Linux is not installed yet.\n" +
                "Open Linux Manager and install Ubuntu, Debian or another ARM64 distro.\n"
        )
    }

    private fun sendInput() {
        val text = input.text.toString()
        if (text.isEmpty()) return
        val active = session
        if (active == null) {
            terminal.feed("\n[LinOx] No Linux shell. Install a distribution first.\n")
            return
        }
        active.write(text + "\n")
        input.text.clear()
    }

    private fun smallButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            setOnClickListener { action() }
        }

    override fun onDestroy() {
        session?.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
