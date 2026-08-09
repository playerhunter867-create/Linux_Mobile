package org.linox.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Real interactive PRoot terminal. */
class TerminalActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var output: TextView
    private lateinit var input: EditText
    private var session: PtySession? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = LinuxRuntime(this)
        buildUi()
        startShell()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.rgb(8, 9, 12))
        }

        val menu = Button(this).apply {
            text = "☰ Menu"
            setOnClickListener { finish() }
        }
        root.addView(menu)

        val scroll = ScrollView(this)
        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(13f)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "LinOx terminal\n\n"
            setPadding(8, 8, 8, 8)
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        input = EditText(this).apply {
            hint = "command"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setOnEditorActionListener { _, _, _ ->
                sendInput()
                true
            }
        }

        val send = Button(this).apply {
            text = "↵"
            setOnClickListener { sendInput() }
        }

        row.addView(input, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(send)
        root.addView(row)

        setContentView(root)
    }

    private fun startShell() {
        executor.execute {
            try {
                session = runtime.startInteractivePty { text ->
                    runOnUiThread {
                        output.append(text)
                    }
                }
                runOnUiThread {
                    output.append("[LinOx] shell started\n")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    output.append("[LinOx] ${t.message ?: t.javaClass.simpleName}\n")
                }
            }
        }
    }

    private fun sendInput() {
        val text = input.text.toString()
        if (text.isEmpty()) return
        session?.write(text + "\n")
        input.text.clear()
    }

    override fun onDestroy() {
        session?.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
