package org.linox.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.linox.mobile.terminal.TerminalView

/** LinOx terminal UI. Keeps the existing PTY/runtime path intact while making the command bar clearer. */
class TerminalActivity : AppCompatActivity() {
    private lateinit var runtime: LinuxRuntime
    private lateinit var terminal: TerminalView
    private lateinit var input: EditText
    private lateinit var setupBanner: LinearLayout
    private var pty: PtySession? = null
    private val history = ArrayList<String>()
    private var historyIndex = 0
    private var started = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        runtime = LinuxRuntime(this)
        buildUi()
        startPty()
    }

    override fun onResume() {
        super.onResume()
        if (!started && runtime.isLinuxReady()) startPty()
        setupBanner.visibility = if (runtime.isLinuxReady()) LinearLayout.GONE else LinearLayout.VISIBLE
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 15, 18))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "🐧 LinOx"
            textSize = 18f
            setTextColor(0xffb95cff.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val menu = Button(this).apply {
            text = "☰ Menu"
            setOnClickListener {
                startActivity(android.content.Intent(this@TerminalActivity, MainActivity::class.java))
            }
        }
        topBar.addView(title)
        topBar.addView(menu)
        root.addView(topBar)

        setupBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = GradientDrawable().apply {
                cornerRadius = 22f
                setColor(Color.rgb(30, 24, 40))
                setStroke(1, 0xff5f3d78.toInt())
            }
            visibility = LinearLayout.GONE
        }
        setupBanner.addView(TextView(this).apply {
            text = "Linux environment isn’t ready"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        setupBanner.addView(TextView(this).apply {
            text = "Install PRoot and an ARM64 Debian/Ubuntu userspace to start the real shell."
            setTextColor(0xffc9c2d0.toInt())
            textSize = 13f
            setPadding(0, 6, 0, 12)
        })
        val setupBtn = Button(this).apply {
            text = "INSTALL LINUX"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minHeight = (54 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                startActivity(android.content.Intent(this@TerminalActivity, DistroActivity::class.java))
            }
        }
        setupBanner.addView(setupBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(setupBanner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(10, 4, 10, 8) })

        terminal = TerminalView(this)
        root.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 10)
        }

        val prompt = TextView(this).apply {
            text = "root@linox:~#"
            setTextColor(0xffb95cff.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 0, 8, 0)
        }

        input = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xff77727d.toInt())
            hint = "command"
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 0, 12, 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(0xff191b20.toInt())
                setStroke(1, 0xff343840.toInt())
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    submitCommand()
                    true
                } else false
            }
            setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_ENTER -> { submitCommand(); true }
                    KeyEvent.KEYCODE_DEL -> { sendText("\u007f"); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { sendText("\u001b[A"); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { sendText("\u001b[B"); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { sendText("\u001b[D"); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { sendText("\u001b[C"); true }
                    else -> false
                }
            }
        }

        val send = Button(this).apply {
            text = "↵"
            textSize = 20f
            setOnClickListener { submitCommand() }
        }
        val ctrl = Button(this).apply {
            text = "Ctrl+C"
            setOnClickListener { pty?.interrupt() }
        }

        bar.addView(prompt)
        bar.addView(input, LinearLayout.LayoutParams(0, (50 * resources.displayMetrics.density).toInt(), 1f))
        bar.addView(send, LinearLayout.LayoutParams((52 * resources.displayMetrics.density).toInt(), (50 * resources.displayMetrics.density).toInt()))
        bar.addView(ctrl)
        root.addView(bar)
        setContentView(root)
    }

    private fun submitCommand() {
        val command = input.text.toString()
        if (command.isBlank()) {
            sendText("\r")
            return
        }
        if (pty == null) {
            terminal.feed("\r\n[LinOx] Shell is not running. Install/activate Linux first.\r\n")
            return
        }
        sendText(command + "\r")
        input.text.clear()
        input.requestFocus()
    }

    private fun startPty() {
        if (!runtime.isLinuxReady()) {
            setupBanner.visibility = LinearLayout.VISIBLE
            terminal.feed(
                "[LinOx] No Linux distribution installed yet.\r\n" +
                    "[LinOx] Tap INSTALL LINUX above to get set up.\r\n"
            )
            return
        }
        try {
            pty = runtime.startInteractivePty { data -> runOnUiThread { terminal.feed(data) } }
            started = true
            setupBanner.visibility = LinearLayout.GONE
            terminal.postDelayed({
                pty?.let {
                    val (c, r) = terminal.dimensions()
                    it.resize(r, c)
                }
            }, 250)
        } catch (e: Exception) {
            terminal.feed("[LinOx] ${e.message}\r\n")
        }
        input.requestFocus()
    }

    private fun sendText(s: String) {
        if (s.isEmpty()) return
        val session = pty
        if (session == null) {
            terminal.feed("\r\n[LinOx] PTY is not available. Install/activate Linux first.\r\n")
            return
        }
        session.write(s)
        if (s.trim().isNotEmpty() && !s.contains("\u001b")) {
            history.add(s.trim())
            historyIndex = history.size
        }
    }

    override fun onDestroy() {
        pty?.close()
        pty = null
        started = false
        super.onDestroy()
    }
}
